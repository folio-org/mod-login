package org.folio.logintest;

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
import org.folio.rest.RestVerticle;
import org.folio.rest.client.TenantClient;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.UnsupportedEncodingException;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;


@RunWith(VertxUnitRunner.class)
public class LoginAttemptsTest {

  private static Vertx vertx;
  private static RequestSpecification spec;

  private static final String TENANT = "diku";
  private static final String TABLE_NAME_ATTEMPTS = "auth_attempts";
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

  @BeforeClass
  public static void setup(final TestContext context) throws Exception {
    Async async = context.async();
    vertx = Vertx.vertx();

    int port = NetworkUtils.nextFreePort();
    int mockPort = NetworkUtils.nextFreePort();
    TenantClient tenantClient = new TenantClient("http://localhost:" + port, "diku", "diku", false);

    DeploymentOptions options = new DeploymentOptions().setConfig(
      new JsonObject()
        .put("http.port", port)
    );

    DeploymentOptions mockOptions = new DeploymentOptions().setConfig(
      new JsonObject()
        .put("port", mockPort)).setWorker(true);

    try {
      PostgresClient.setIsEmbedded(true);
      PostgresClient.getInstance(vertx).startEmbeddedPostgres();
    } catch (Exception e) {
      context.fail(e);
      return;
    }

    vertx.deployVerticle(UserMock.class.getName(), mockOptions, mockRes -> {
      if (mockRes.failed()) {
        mockRes.cause().printStackTrace();
        context.fail(mockRes.cause());
      } else {
        vertx.deployVerticle(RestVerticle.class.getName(), options, res -> {
          try {
            tenantClient.postTenant(null, res2 -> {
              async.complete();
            });
          } catch (Exception e) {
            e.printStackTrace();
          }
        });
      }
    });

    spec = new RequestSpecBuilder()
      .setContentType(ContentType.JSON)
      .setBaseUri("http://localhost:" + port)
      .addHeader("x-okapi-url", "http://localhost:" + mockPort)
      .addHeader(RestVerticle.OKAPI_HEADER_TENANT, TENANT)
      .addHeader(RestVerticle.OKAPI_HEADER_TOKEN, "dummy.token")
      .build();
  }

  @Before
  public void setUp(TestContext context) {
    Async async = context.async();
    PostgresClient pgClient = PostgresClient.getInstance(vertx, "diku");
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

  @AfterClass
  public static void teardown(TestContext context) {
    Async async = context.async();

    PostgresClient.getInstance(vertx, TENANT).delete(TABLE_NAME_ATTEMPTS, new Criterion(), event -> {
      if (event.failed()) {
        context.fail(event.cause());
      } else {
        try {
          PostgresClient.getInstance(vertx, TENANT).delete("auth_credentials", new Criterion(), r -> {
            if (r.failed()) {
              context.fail(r.cause());
            }
          });
        } catch (Exception e) {
          context.fail(e);
        }
      }
    });
    vertx.close(context.asyncAssertSuccess(res -> {
      PostgresClient.stopEmbeddedPostgres();
      async.complete();
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
      .statusCode(201);

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
      .body("errors[0].code", equalTo("fifth.failed.attempt.blocked"));

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
}
