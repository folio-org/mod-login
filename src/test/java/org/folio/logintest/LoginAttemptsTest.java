package org.folio.logintest;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.util.LoginAttemptsHelper.*;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.folio.okapi.common.XOkapiHeaders;
import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.Password;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.tools.utils.NetworkUtils;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;

@RunWith(VertxUnitRunner.class)
public class LoginAttemptsTest {

  private static Vertx vertx;
  private static RequestSpecification spec;

  private static final String TENANT_DIKU = "diku";
  private static final String CRED_PATH = "/authn/credentials";
  private static final String ATTEMPTS_PATH = "/authn/loginAttempts";
  private static final String LOGIN_PATH = "/authn/login";
  private static final String UPDATE_PATH = "/authn/update";
  private static final String adminId = "8bd684c1-bbc3-4cf1-bcf4-8013d02a94ce";

  private JsonObject credsObject8 = new JsonObject()
    .put("username", "admin")
    .put("password", "admin1")
    .put("userId", adminId);

  private JsonObject credsObject8Fail = new JsonObject()
    .put("username", "admin")
    .put("password", "admin")
    .put("userId", adminId);

  private JsonObject credsObject8Update = new JsonObject()
    .put("username", "admin")
    .put("password", "admin1")
    .put("newPassword", "admin2")
    .put("userId", adminId);

  private JsonObject credsObject8Login = new JsonObject()
    .put("username", "admin")
    .put("password", "admin2")
    .put("userId", adminId);

  private static Map<String, String> moduleArgs;

  @BeforeClass
  public static void setup(final TestContext context) throws Exception {
    moduleArgs = new HashMap<String,String>(MODULE_SPECIFIC_ARGS);
    vertx = Vertx.vertx();

    int port = NetworkUtils.nextFreePort();
    int mockPort = NetworkUtils.nextFreePort();

    DeploymentOptions options = new DeploymentOptions().setConfig(
      new JsonObject()
        .put("http.port", port)
    );

    DeploymentOptions mockOptions = new DeploymentOptions().setConfig(
      new JsonObject()
        .put("port", mockPort));

    try {
      PostgresClient.setPostgresTester(new PostgresTesterContainer());
      PostgresClient.getInstance(vertx);
    } catch (Exception e) {
      context.fail(e);
      return;
    }

    spec = new RequestSpecBuilder()
        .setContentType(ContentType.JSON)
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader(XOkapiHeaders.TOKEN, "dummy.token")
        .addHeader(XOkapiHeaders.REQUEST_TIMESTAMP, String.valueOf(new Date().getTime()))
        .build();

    vertx.deployVerticle(UserMock.class.getName(), mockOptions)
        .onComplete(context.asyncAssertSuccess(res ->
            vertx.deployVerticle(RestVerticle.class.getName(), options)
                .onComplete(context.asyncAssertSuccess(res2 -> {
                  TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
                  TestUtil.postSync(ta, TENANT_DIKU, port, vertx).onComplete(context.asyncAssertSuccess());
                }))
        ));
  }

  @Before
  public void setUp(TestContext context) {
    MODULE_SPECIFIC_ARGS.clear();
    MODULE_SPECIFIC_ARGS.putAll(moduleArgs);
    UserMock.resetConfigs();
    Async async = context.async();
    PostgresClient pgClient = PostgresClient.getInstance(vertx, TENANT_DIKU);
    pgClient.startTx(beginTx ->
      pgClient.delete(beginTx, "auth_attempts", new Criterion(), event -> {
        if (event.failed()) {
          pgClient.rollbackTx(beginTx, e -> context.fail(event.cause()));
        } else {
          pgClient.delete(beginTx, "auth_credentials", new Criterion(), eventAuth -> {
            if (eventAuth.failed()) {
              pgClient.rollbackTx(beginTx, e -> context.fail(eventAuth.cause()));
            } else { // auth_credentials_history
              pgClient.delete(beginTx, "auth_credentials_history", new Criterion(), eventHistory -> {
                if (eventHistory.failed()) {
                  pgClient.rollbackTx(beginTx, e -> context.fail(eventHistory.cause()));
                } else {
                  pgClient.endTx(beginTx, ev -> {
                    event.succeeded();
                    async.complete();
                  });
                }
              });
            }
          });
        }
      }));
  }

  @Test
  public void testAttempts(final TestContext context) throws UnsupportedEncodingException {
    RestAssured.given()
      .spec(spec)
      .body(credsObject8.encode())
      .when()
      .post(CRED_PATH)
      .then()
      .log().all()
      .statusCode(201);

    RestAssured.given()
      .spec(spec)
      .body(credsObject8Fail.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(422)
      .body("errors[0].code", equalTo("password.incorrect"));

    RestAssured.given()
      .spec(spec)
      .when()
      .get(ATTEMPTS_PATH + "/" + adminId)
      .then()
      .log().all()
      .statusCode(200)
      .body("attemptCount", is(1));

    RestAssured.given()
      .spec(spec)
      .body(credsObject8.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(201)
      .body("okapiToken", is("dummytoken"))
      .body("refreshToken", is("dummyrefreshtoken"))
      .header(XOkapiHeaders.TOKEN, is("dummytoken"))
      .header("refreshtoken", is("dummyrefreshtoken"));

    RestAssured.given()
      .spec(spec)
      .when()
      .get(ATTEMPTS_PATH + "/" + adminId)
      .then()
      .log().all()
      .statusCode(200)
      .body("attemptCount", is(0));

    RestAssured.given()
      .spec(spec)
      .body(credsObject8Fail.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(422)
      .body("errors[0].code", equalTo("password.incorrect"));

    RestAssured.given()
      .spec(spec)
      .when()
      .get(ATTEMPTS_PATH + "/" + adminId)
      .then()
      .log().all()
      .statusCode(200)
      .body("attemptCount", is(1));

    RestAssured.given()
      .spec(spec)
      .body(credsObject8Update.encode())
      .when()
      .post(UPDATE_PATH)
      .then()
      .log().all()
      .statusCode(204);

    RestAssured.given()
      .spec(spec)
      .when()
      .get(ATTEMPTS_PATH + "/" + adminId)
      .then()
      .log().all()
      .statusCode(200)
      .body("attemptCount", is(0));

    RestAssured.given()
      .spec(spec)
      .body(credsObject8Fail.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(422)
      .body("errors[0].code", equalTo("password.incorrect"));

    RestAssured.given()
      .spec(spec)
      .when()
      .get(ATTEMPTS_PATH + "/" + adminId)
      .then()
      .log().all()
      .statusCode(200)
      .body("attemptCount", is(1));

    RestAssured.given()
      .spec(spec)
      .body(credsObject8Fail.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(422)
      .body("errors[0].code", equalTo("password.incorrect.block.user"));

    RestAssured.given()
      .spec(spec)
      .body(credsObject8Login.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(422)
      .body("errors[0].code", equalTo("user.blocked"));

    RestAssured.given()
      .spec(spec)
      .when()
      .get(ATTEMPTS_PATH + "/" + adminId)
      .then()
      .log().all()
      .statusCode(200)
      .body("attemptCount", is(0));
  }

  @Test
  public void testConfiguration(final TestContext context) throws UnsupportedEncodingException {
    MODULE_SPECIFIC_ARGS.remove(LOGIN_ATTEMPTS_CODE);
    MODULE_SPECIFIC_ARGS.remove(LOGIN_ATTEMPTS_TIMEOUT_CODE);
    MODULE_SPECIFIC_ARGS.remove(LOGIN_ATTEMPTS_TO_WARN_CODE);

    UserMock.setConfig(LOGIN_ATTEMPTS_CODE, new JsonObject().put("totalRecords", 0)); // fallback to hardcoded value (5)
    UserMock.removeConfig(LOGIN_ATTEMPTS_TO_WARN_CODE); // fallback to hardcoded value (3)
    UserMock.setConfig(LOGIN_ATTEMPTS_TIMEOUT_CODE, new JsonObject()); // fallback to hardcoded value (10)

    RestAssured.given()
      .spec(spec)
      .body(credsObject8.encode())
      .when()
      .post(CRED_PATH)
      .then()
      .log().all()
      .statusCode(201);

    RestAssured.given()
      .spec(spec)
      .body(credsObject8Fail.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(422)
      .body("errors[0].code", equalTo("password.incorrect"));

    RestAssured.given()
      .spec(spec)
      .body(credsObject8Fail.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(422)
      .body("errors[0].code", equalTo("password.incorrect"));

    RestAssured.given()
      .spec(spec)
      .body(credsObject8Fail.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(422)
      .body("errors[0].code", equalTo("password.incorrect.warn.user"));

    RestAssured.given()
      .spec(spec)
      .body(credsObject8.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(201);
  }

  @Test
  public void shouldReturnValidationErrorWithNoPassword() {
    RestAssured.given()
      .spec(spec)
      .header(XOkapiHeaders.USER_ID, adminId)
      .body(new Password().withUserId(UUID.randomUUID().toString()))
      .when()
      .post(CRED_PATH)
      .then()
      .statusCode(422)
      .body("errors[0].message", equalTo("Password is missing or empty"));
  }
}
