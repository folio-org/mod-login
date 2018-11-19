package org.folio.logintest;

import com.github.tomakehurst.wiremock.common.ConsoleNotifier;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.restassured.RestAssured;
import io.restassured.http.Header;
import io.restassured.http.Headers;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.AsyncResult;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.http.HttpStatus;
import org.folio.rest.RestVerticle;
import org.folio.rest.client.TenantClient;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.Metadata;
import org.folio.rest.jaxrs.model.PasswordReset;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.folio.util.AuthUtil;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

import javax.ws.rs.core.MediaType;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.fail;
import static org.folio.rest.RestVerticle.OKAPI_HEADER_TENANT;
import static org.folio.rest.RestVerticle.OKAPI_HEADER_TOKEN;
import static org.folio.util.LoginConfigUtils.SNAPSHOTS_TABLE_CREDENTIALS;
import static org.folio.util.LoginConfigUtils.SNAPSHOTS_TABLE_PW;

@RunWith(VertxUnitRunner.class)
public class PasswordResetActionTest {

  private static final String TENANT_ID = "diku";
  private static final String OKAPI_TOKEN_VAL = "test_token";
  private static final String HTTP_PORT = "http.port";
  private static final String PATH_TEMPLATE = "%s/%s";
  private static final String USER_PW = "OldPassword";

  private static RequestSpecification request;
  private static String restPathPasswordAction;
  private static String restPathResetPassword;
  private static String restPathGetUserCredential;
  private static Vertx vertx;
  private String userIdSetUp;
  private String authIdSetUp;

  @Rule
  public Timeout timeout = Timeout.seconds(200);

  @Rule
  public WireMockRule userMockServer = new WireMockRule(
    WireMockConfiguration.wireMockConfig()
      .dynamicPort()
      .notifier(new ConsoleNotifier(true)));

  @BeforeClass
  public static void setUpClass(final TestContext context) {
    Async async = context.async();
    vertx = Vertx.vertx();
    int port = NetworkUtils.nextFreePort();
    Headers headers = new Headers(
      new Header(OKAPI_HEADER_TENANT, TENANT_ID),
      new Header(OKAPI_HEADER_TOKEN, OKAPI_TOKEN_VAL));
    restPathPasswordAction = System.getProperty("org.folio.password.action.path", "/authn/password-reset-action");
    restPathResetPassword = System.getProperty("org.folio.password.reset.path", "/authn/reset-password");
    restPathGetUserCredential = System.getProperty("org.folio.password.get.credential", "/authn/credentials/");
    request = RestAssured.given()
      .port(port)
      .contentType(MediaType.APPLICATION_JSON)
      .headers(headers);

    try {
      PostgresClient.setIsEmbedded(true);
      PostgresClient.getInstance(vertx).startEmbeddedPostgres();
    } catch (Exception e) {
      context.fail(e);
    }

    TenantClient tenantClient = new TenantClient("localhost", port, TENANT_ID, OKAPI_TOKEN_VAL);
    DeploymentOptions restDeploymentOptions = new DeploymentOptions()
      .setConfig(new JsonObject().put(HTTP_PORT, port));

    vertx.deployVerticle(RestVerticle.class.getName(), restDeploymentOptions,
      res -> {
        try {
          tenantClient.postTenant(null, handler -> async.complete());
        } catch (Exception e) {
          context.fail(e);
        }
      });
  }

  @AfterClass
  public static void tearDownClass(final TestContext context) {
    Async async = context.async();
    vertx.close(context.asyncAssertSuccess(
      res -> {
        PostgresClient.stopEmbeddedPostgres();
        async.complete();
      }));
  }

  @Before
  public void setUp(TestContext context) {
    userIdSetUp = UUID.randomUUID().toString();
    PostgresClient pgClient = PostgresClient.getInstance(vertx, TENANT_ID);

    pgClient.startTx(beginTx ->
      pgClient.delete(beginTx, SNAPSHOTS_TABLE_PW, new Criterion(), event -> {
        if (event.failed()) {
          pgClient.rollbackTx(beginTx, e -> {
            fail();
            context.fail(event.cause());
          });
        }
        deleteAuthCredentials(context, pgClient, beginTx, userIdSetUp, event);
      }));
  }

  @Test
  public void testCreateNewPasswordAction() {
    String id = UUID.randomUUID().toString();
    String userId = UUID.randomUUID().toString();
    JsonObject expectedJson = createPasswordAction(id, userId, new Date());

    Response response = requestPostCreatePasswordAction(expectedJson)
      .then()
      .statusCode(HttpStatus.SC_CREATED)
      .extract()
      .response();

    JsonObject actualJson = new JsonObject(response.getBody().print());
    assertEquals(expectedJson, actualJson);
  }

  @Test
  public void testCrudNewPasswordAction() {
    String id = UUID.randomUUID().toString();
    String userId = UUID.randomUUID().toString();
    JsonObject expectedJson = createPasswordAction(id, userId, new Date());

    // create a new password action
    requestPostCreatePasswordAction(expectedJson)
      .then()
      .statusCode(HttpStatus.SC_CREATED);

    // find the password action by id
    Response response = requestGetCreatePasswordAction(id)
      .then()
      .statusCode(HttpStatus.SC_OK)
      .extract()
      .response();

    JsonObject actualJson = new JsonObject(response.getBody().print());
    assertEquals(expectedJson, actualJson);
  }

  @Test
  public void testGetPasswordActionById() {
    String incorrectId = UUID.randomUUID().toString();
    requestGetCreatePasswordAction(incorrectId)
      .then()
      .statusCode(HttpStatus.SC_NOT_FOUND);
  }

  @Test
  public void testPostResetPassword() {
    String id = UUID.randomUUID().toString();
    String password = UUID.randomUUID().toString();
    JsonObject passwordReset = createPasswordReset(id, password);
    requestPostResetPassword(passwordReset)
      .then()
      .statusCode(HttpStatus.SC_NOT_FOUND);
  }

  @Test
  public void testResetPasswordUserIsExist() {
    String id = UUID.randomUUID().toString();
    String userId = userIdSetUp;
    JsonObject passwordAction = createPasswordAction(id, userId, new Date());

    // create a new password action
    requestPostCreatePasswordAction(passwordAction)
      .then()
      .statusCode(HttpStatus.SC_CREATED);

    // reset password action
    String newPassword = UUID.randomUUID().toString();
    JsonObject passwordReset = createPasswordReset(id, newPassword);
    requestPostResetPassword(passwordReset)
      .then()
      .statusCode(HttpStatus.SC_CREATED);

    // check by id
    requestGetCreatePasswordAction(id)
      .then()
      .statusCode(HttpStatus.SC_NOT_FOUND);

    // check new user's password
    Response response = requestGetUserCredential(authIdSetUp)
      .then()
      .statusCode(HttpStatus.SC_OK)
      .extract()
      .response();

    JsonObject userCredentials = new JsonObject(response.getBody().prettyPrint());
    String hash = userCredentials.getString("hash");
    String salt = userCredentials.getString("salt");
    assertEquals(new AuthUtil().calculateHash(newPassword, salt), hash);
  }

  @Test
  public void testResetPasswordUserNotFound() {
    String id = UUID.randomUUID().toString();
    String userId = UUID.randomUUID().toString();
    JsonObject passwordAction = createPasswordAction(id, userId, new Date());

    // create a new password action
    requestPostCreatePasswordAction(passwordAction)
      .then()
      .statusCode(HttpStatus.SC_CREATED);

    // reset password action: user not fount
    String password = UUID.randomUUID().toString();
    JsonObject passwordReset = createPasswordReset(id, password);
    requestPostResetPassword(passwordReset)
      .then()
      .statusCode(HttpStatus.SC_NOT_FOUND);

    // check by id: Action is exist
    requestGetCreatePasswordAction(id)
      .then()
      .statusCode(HttpStatus.SC_OK);
  }

  private void deleteAuthCredentials(TestContext context, PostgresClient pgClient, AsyncResult<SQLConnection> beginTx,
                                     String userIdSetUp, AsyncResult<UpdateResult> event) {
    pgClient.delete(beginTx, SNAPSHOTS_TABLE_CREDENTIALS, new Criterion(), eventAuth -> {
      if (event.failed()) {
        pgClient.rollbackTx(beginTx, e -> {
          fail();
          context.fail(event.cause());
        });
      }
      generateUser(pgClient, beginTx, context, userIdSetUp);
    });
  }

  private void generateUser(PostgresClient pgClient, AsyncResult<SQLConnection> beginTx,
                            TestContext context, String userId) {
    Metadata metadata = new Metadata().withCreatedDate(new Date());
    AuthUtil authUtil = new AuthUtil();
    authIdSetUp = UUID.randomUUID().toString();

    Credential credential = new Credential();
    credential.setId(authIdSetUp);            // generate ID
    credential.setUserId(userId);
    credential.setSalt(authUtil.getSalt());
    credential.setHash(authUtil.calculateHash(USER_PW, credential.getSalt()));
    credential.setMetadata(metadata);

    pgClient.save(beginTx, SNAPSHOTS_TABLE_CREDENTIALS, credential.getId(), credential, event -> {
      if (event.failed()) {
        pgClient.rollbackTx(beginTx, e -> {
          fail();
          context.fail(event.cause());
        });
      }
      pgClient.endTx(beginTx, AsyncResult::succeeded);
    });
  }

  // Request POST: "/authn/password-reset-action"
  private Response requestPostCreatePasswordAction(JsonObject expectedEntity) {
    return request.body(expectedEntity.toString())
      .when()
      .post(restPathPasswordAction);
  }

  // Request GET: "/authn/password-reset-action"
  private Response requestGetCreatePasswordAction(String expectedId) {
    return request
      .when()
      .get(String.format(PATH_TEMPLATE, restPathPasswordAction, expectedId));
  }

  // Request POST: "/authn/reset-password"
  private Response requestPostResetPassword(JsonObject expectedEntity) {
    return request.body(expectedEntity.toString())
      .when()
      .post(restPathResetPassword);
  }

  // Request GET: "/authn/credentials/{id}"
  private Response requestGetUserCredential(String id) {
    return request
      .when()
      .get(restPathGetUserCredential + id);
  }

  private JsonObject createPasswordAction(String id, String userId, Date date) {
    DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    String dateOffsetUtc = df.format(date);
    return new JsonObject()
      .put("id", id)
      .put("userId", userId)
      .put("expirationTime", dateOffsetUtc);
  }

  private JsonObject createPasswordReset(String id, String password) {
    PasswordReset passwordReset = new PasswordReset()
      .withNewPassword(password)
      .withPasswordResetActionId(id);
    return JsonObject.mapFrom(passwordReset);
  }
}
