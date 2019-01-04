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
import org.folio.rest.impl.LoginAPI;
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

import static junit.framework.TestCase.*;
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
      new Header(OKAPI_HEADER_TOKEN, OKAPI_TOKEN_VAL),
      new Header(LoginAPI.OKAPI_REQUEST_TIMESTAMP_HEADER, String.valueOf(new Date().getTime())));
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
      res ->
      {
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
      res ->
      {
        PostgresClient.stopEmbeddedPostgres();
        async.complete();
      }));
  }

  @Before
  public void before(TestContext context) {
    Async async = context.async();
    PostgresClient pgClient = PostgresClient.getInstance(vertx, TENANT_ID);
    pgClient.startTx(beginTx ->
      pgClient.delete(beginTx, SNAPSHOTS_TABLE_PW, new Criterion(), event ->
      {
        if (event.failed()) {
          pgClient.rollbackTx(beginTx, e ->
          {
            fail();
            context.fail(event.cause());
          });
        }
        deleteAuthCredentials(context, pgClient, beginTx,async, event);
      }));
  }

  @Test
  public void testCreateNewPasswordActionWhenUserIsNotExist() {
    String id = UUID.randomUUID().toString();
    String userId = UUID.randomUUID().toString();
    JsonObject passwordAction = createPasswordAction(id, userId, new Date());
    JsonObject expectedJson = new JsonObject().put("passwordExists", false);

    Response response = requestPostCreatePasswordAction(passwordAction)
      .then()
      .statusCode(HttpStatus.SC_CREATED)
      .extract()
      .response();

    // if the user hasn't credential then `passwordExists` is false
    JsonObject actualJson = new JsonObject(response.getBody().print());
    assertEquals(expectedJson, actualJson);
  }

  @Test
  public void testCreateNewPasswordActionWhenUserIsExist() {
    String id = UUID.randomUUID().toString();
    JsonObject userCred = getUserCredentials();
    String userId = userCred.getString("userId");
    JsonObject passwordAction = createPasswordAction(id, userId, new Date());
    JsonObject expectedJson = new JsonObject().put("passwordExists", true);

    Response response = requestPostCreatePasswordAction(passwordAction)
      .then()
      .statusCode(HttpStatus.SC_CREATED)
      .extract()
      .response();

    // if the user has credential then `passwordExists` is true
    JsonObject actualJson = new JsonObject(response.getBody().print());
    assertEquals(expectedJson, actualJson);
  }

  @Test
  public void testCrudNewPasswordActionWhenUserIsNotExist() {
    String id = UUID.randomUUID().toString();
    String userId = UUID.randomUUID().toString();
    JsonObject expectedJson = createPasswordAction(id, userId, new Date());

    // create a new password action
    Response response = requestPostCreatePasswordAction(expectedJson)
      .then()
      .statusCode(HttpStatus.SC_CREATED)
      .extract()
      .response();

    // if the user hasn't credential then `passwordExists` is false
    JsonObject respActionJson = new JsonObject(response.getBody().print());
    assertFalse(respActionJson.getBoolean("passwordExists"));

    // find the password action by id
    response = requestGetCreatePasswordAction(id)
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
  public void testPostResetPasswordWhenUserIsNotExist() {
    String id = UUID.randomUUID().toString();
    String password = UUID.randomUUID().toString();
    JsonObject passwordReset = createPasswordReset(id, password);
    requestPostResetPassword(passwordReset)
      .then()
      .statusCode(HttpStatus.SC_BAD_REQUEST);
  }

  @Test
  public void testResetPasswordWhenUserIsExist() {
    String id = UUID.randomUUID().toString();
    JsonObject userCred = getUserCredentials();
    String userId = userCred.getString("userId");
    String credId = userCred.getString("id");
    JsonObject passwordAction = createPasswordAction(id, userId, new Date());

    // create a new password action
    Response response = requestPostCreatePasswordAction(passwordAction)
      .then()
      .statusCode(HttpStatus.SC_CREATED)
      .extract()
      .response();

    JsonObject respActionJson = new JsonObject(response.getBody().print());
    assertTrue(respActionJson.getBoolean("passwordExists"));

    // reset password action
    String newPassword = UUID.randomUUID().toString();
    JsonObject passwordReset = createPasswordReset(id, newPassword);
    response = requestPostResetPassword(passwordReset)
      .then()
      .statusCode(HttpStatus.SC_CREATED)
      .extract()
      .response();

    // if the user has credential then `isNewPassword` is false
    respActionJson = new JsonObject(response.getBody().print());
    assertFalse(respActionJson.getBoolean("isNewPassword"));

    // check by id
    requestGetCreatePasswordAction(id)
      .then()
      .statusCode(HttpStatus.SC_NOT_FOUND);

    // check new user's password
    response = requestGetUserCredential(credId)
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
    Response response = requestPostCreatePasswordAction(passwordAction)
      .then()
      .statusCode(HttpStatus.SC_CREATED).extract()
      .response();

    // if the user hasn't credential then `passwordExists` is false
    JsonObject respActionJson = new JsonObject(response.getBody().print());
    assertFalse(respActionJson.getBoolean("passwordExists"));

    // reset password action: user not fount
    String password = UUID.randomUUID().toString();
    JsonObject passwordReset = createPasswordReset(id, password);
    response = requestPostResetPassword(passwordReset)
      .then()
      .statusCode(HttpStatus.SC_CREATED)
      .extract()
      .response();

    // if the user hasn't credential then `isNewPassword` is true
    respActionJson = new JsonObject(response.getBody().print());
    assertTrue(respActionJson.getBoolean("isNewPassword"));

    // check by id: Action was deleted
    requestGetCreatePasswordAction(id)
      .then()
      .statusCode(HttpStatus.SC_NOT_FOUND);
  }

  private void deleteAuthCredentials(TestContext context, PostgresClient pgClient, AsyncResult<SQLConnection> beginTx,
                                     Async async, AsyncResult<UpdateResult> event) {
    pgClient.delete(beginTx, SNAPSHOTS_TABLE_CREDENTIALS, new Criterion(), eventAuth ->
    {
      if (event.failed()) {
        pgClient.rollbackTx(beginTx, e ->
        {
          fail();
          context.fail(event.cause());
        });
      }
      pgClient.endTx(beginTx, ev -> {
          event.succeeded();
          async.complete();
        });
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

  // Request POST: "/authn/credentials"
  private JsonObject getUserCredentials() {
    JsonObject userCredentials = new JsonObject()
      .put("username", "user")
      .put("userId", UUID.randomUUID().toString())
      .put("password", USER_PW);

    String body = request
      .body(userCredentials.encode())
      .when()
      .post("/authn/credentials") //
      .then()
      .extract()
      .response()
      .getBody().prettyPrint();
    return new JsonObject(body);
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
