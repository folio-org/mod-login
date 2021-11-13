package org.folio.logintest;

import static org.hamcrest.Matchers.is;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.folio.okapi.common.XOkapiHeaders;
import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.CredentialsHistory;
import org.folio.rest.jaxrs.model.Password;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.folio.util.AuthUtil;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.CompositeFuture;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;


@RunWith(VertxUnitRunner.class)
public class PasswordRepeatabilityValidationTest {

  private static Vertx vertx;
  private static RequestSpecification spec;
  private static AuthUtil authUtil = new AuthUtil();

  private static final String USER_ID = "e341d8bb-5d5d-4ce8-808c-b2e9bbfb4a1a";
  private static final String TENANT = "diku";
  private static final String TABLE_NAME_CREDENTIALS = "auth_credentials";
  private static final String TABLE_NAME_CREDENTIALS_HISTORY = "auth_credentials_history";
  private static final String PASSWORD_REAPITABILITY_VALIDATION_PATH = "/authn/password/repeatable";
  private static final String PASSWORD_TEMPLATE = "Admin!@1";
  private static final String NEW_PASSWORD = "Newpwd!@1";
  private static final String OLD_PASSWORD = "Oldpwd!@1";
  private static final String CURRENT_PASSWORD = "Current!@1";
  private static final int PASSWORDS_HISTORY_NUMBER = 10;
  private static final String RESULT_JSON_PATH = "result";
  private static final String VALID = "valid";
  private static final String INVALID = "invalid";

  @BeforeClass
  public static void setup(final TestContext context) {
    vertx = Vertx.vertx();
    int port = NetworkUtils.nextFreePort();

    try {
      PostgresClient.setPostgresTester(new PostgresTesterContainer());
      PostgresClient.getInstance(vertx);
    } catch (Exception e) {
      context.fail(e);
    }

    spec = new RequestSpecBuilder()
        .setContentType(ContentType.JSON)
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.TENANT, TENANT)
        .addHeader(XOkapiHeaders.TOKEN, "dummytoken")
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + port)
        .build();

    DeploymentOptions restVerticleDeploymentOptions =
      new DeploymentOptions().setConfig(new JsonObject().put("http.port", port));
    vertx.deployVerticle(RestVerticle.class.getName(), restVerticleDeploymentOptions)
        .onComplete(context.asyncAssertSuccess(res -> {
      TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
      TestUtil.postSync(ta, TENANT, port, vertx).onComplete(context.asyncAssertSuccess(res2 ->
        fillInCredentialsHistory().compose(x -> saveCredential()).onComplete(context.asyncAssertSuccess())
      ));
    }));
  }

  @Test
  public void testNewPassword() {
    RestAssured.given()
      .spec(spec)
      .body(buildPasswordEntity(NEW_PASSWORD))
      .when()
      .post(PASSWORD_REAPITABILITY_VALIDATION_PATH)
      .then()
      .statusCode(200)
      .body(RESULT_JSON_PATH, is(VALID));
  }

  @Test
  public void testRecentPassword() {
    RestAssured.given()
      .spec(spec)
      .body(buildPasswordEntity(PASSWORD_TEMPLATE + 1))
      .when()
      .post(PASSWORD_REAPITABILITY_VALIDATION_PATH)
      .then()
      .statusCode(200)
      .body(RESULT_JSON_PATH, is(INVALID));
  }

  @Test
  public void testOldPassword() {
    RestAssured.given()
      .spec(spec)
      .body(buildPasswordEntity(OLD_PASSWORD))
      .when()
      .post(PASSWORD_REAPITABILITY_VALIDATION_PATH)
      .then()
      .statusCode(200)
      .body(RESULT_JSON_PATH, is(VALID));
  }

  @Test
  public void testCurrentPassword() {
    RestAssured.given()
      .spec(spec)
      .header(XOkapiHeaders.USER_ID, USER_ID)
      .body(buildPasswordEntity(CURRENT_PASSWORD))
      .when()
      .post(PASSWORD_REAPITABILITY_VALIDATION_PATH)
      .then()
      .statusCode(200)
      .body(RESULT_JSON_PATH, is(INVALID));
  }

  @Test
  public void testNoCredentialsFound() {
    String userId = UUID.randomUUID().toString();
    RestAssured.given()
      .spec(spec)
      .header(XOkapiHeaders.USER_ID, userId)
      .body(buildPasswordEntity(CURRENT_PASSWORD, userId))
      .when()
      .post(PASSWORD_REAPITABILITY_VALIDATION_PATH)
      .then()
      .log().all()
      .statusCode(200)
      .body(RESULT_JSON_PATH, is(VALID));
  }

  private static Future<Void> fillInCredentialsHistory() {
    Promise<CompositeFuture> promise = Promise.promise();

    //add ten passwords
    List<Future> list = IntStream.range(0, PASSWORDS_HISTORY_NUMBER - 1)
      .mapToObj(i -> PASSWORD_TEMPLATE + i)
      .map(PasswordRepeatabilityValidationTest::buildCredentialsHistoryObject)
      .map(PasswordRepeatabilityValidationTest::saveCredentialsHistoryObject)
      .collect(Collectors.toList());

    //add older password
    long hourMillis = 1000 * 60 * 60;
    list.add(saveCredentialsHistoryObject(buildCredentialsHistoryObject(
      OLD_PASSWORD, new Date(new Date().getTime() - hourMillis))));

    CompositeFuture.all(list).onComplete(promise);

    return promise.future().map(s -> null);
  }

  private static Future<Void> saveCredential() {
    Promise<String> promise = Promise.promise();
    String salt = authUtil.getSalt();
    Credential credential = new Credential();
    credential.setId(UUID.randomUUID().toString());
    credential.setSalt(salt);
    credential.setHash(authUtil.calculateHash(CURRENT_PASSWORD, salt));
    credential.setUserId(USER_ID);
    PostgresClient.getInstance(vertx, TENANT)
      .save(TABLE_NAME_CREDENTIALS, UUID.randomUUID().toString(), credential, promise);
    return promise.future().map(s -> null);
  }

  private static Future<Void> saveCredentialsHistoryObject(CredentialsHistory obj) {
    Promise<String> promise = Promise.promise();

    PostgresClient client = PostgresClient.getInstance(vertx, TENANT);
    client.save(TABLE_NAME_CREDENTIALS_HISTORY, UUID.randomUUID().toString(), obj, promise);

    return promise.future().map(s -> null);
  }

  private static CredentialsHistory buildCredentialsHistoryObject(String password) {
    return buildCredentialsHistoryObject(password, new Date());
  }

  private static CredentialsHistory buildCredentialsHistoryObject(String password, Date date) {
    CredentialsHistory history = new CredentialsHistory();
    String salt = authUtil.getSalt();
    history.setId(UUID.randomUUID().toString());
    history.setUserId(USER_ID);
    history.setHash(authUtil.calculateHash(password, salt));
    history.setSalt(salt);
    history.setDate(date);

    return history;
  }

  private Password buildPasswordEntity(String password) {
    return buildPasswordEntity(password, USER_ID);
  }

  private Password buildPasswordEntity(String password, String userId) {
    return new Password()
      .withPassword(password)
      .withUserId(userId);
  }
}
