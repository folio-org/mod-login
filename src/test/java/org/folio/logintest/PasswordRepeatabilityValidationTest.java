package org.folio.logintest;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.CompositeFuture;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.folio.rest.RestVerticle;
import org.folio.rest.client.TenantClient;
import org.folio.rest.jaxrs.model.CredentialsHistory;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.folio.util.AuthUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.is;


@RunWith(VertxUnitRunner.class)
public class PasswordRepeatabilityValidationTest {

  private static Vertx vertx;
  private static RequestSpecification spec;
  private static String userId = UUID.randomUUID().toString();
  private static AuthUtil authUtil = new AuthUtil();

  private static final String TENANT = "diku";
  private static final String TABLE_NAME_CREDENTIALS_HISTORY = "auth_credentials_history";
  private static final String PASSWORD_REAPITABILITY_VALIDATION_PATH = "/authn/password/repeatable";
  private static final String PASSWORD_TEMPLATE = "Admin!@1";
  private static final String NEW_PASSWORD = "Newpwd!@1";
  private static final String OLD_PASSWORD = "Oldpwd!@1";
  private static final int PASSWORD_COUNT = 10;
  private static final String RESULT_JSON_PATH = "result";
  private static final String PASSWORD_JSON_PATH = "password";
  private static final String VALID = "valid";
  private static final String INVALID = "invalid";

  @BeforeClass
  public static void setup(final TestContext context) throws Exception {
    Async async = context.async();
    vertx = Vertx.vertx();
    int port = NetworkUtils.nextFreePort();

    try {
      PostgresClient.setIsEmbedded(true);
      PostgresClient.getInstance(vertx).startEmbeddedPostgres();
    } catch (Exception e) {
      context.fail(e);
    }

    TenantClient tenantClient = new TenantClient("localhost", port, TENANT, "diku");
    DeploymentOptions restVerticleDeploymentOptions =
      new DeploymentOptions().setConfig(new JsonObject().put("http.port", port));
    vertx.deployVerticle(RestVerticle.class.getName(), restVerticleDeploymentOptions, res -> {
      try {
        tenantClient.postTenant(null, handler -> fillInCredentialsHistory(async));
      } catch (Exception e) {
        context.fail(e);
      }
    });

    spec = new RequestSpecBuilder()
      .setContentType(ContentType.JSON)
      .setBaseUri("http://localhost:" + port)
      .addHeader(RestVerticle.OKAPI_HEADER_TENANT, TENANT)
      .build();
  }

  @AfterClass
  public static void teardown(TestContext context) {
    Async async = context.async();

    PostgresClient.getInstance(vertx, TENANT).delete(TABLE_NAME_CREDENTIALS_HISTORY, new Criterion(), event -> {
      if (event.failed()) {
        context.fail(event.cause());
      }
    });

    vertx.close(context.asyncAssertSuccess( res-> {
      PostgresClient.stopEmbeddedPostgres();
      async.complete();
    }));
  }

  @Test
  public void testNewPassword(final TestContext context) throws InterruptedException {
    RestAssured.given()
      .spec(spec)
      .header(RestVerticle.OKAPI_USERID_HEADER, userId)
      .body(new JsonObject().put(PASSWORD_JSON_PATH, NEW_PASSWORD).encode())
      .when()
      .post(PASSWORD_REAPITABILITY_VALIDATION_PATH)
      .then()
      .statusCode(200)
      .body(RESULT_JSON_PATH, is(VALID));
  }

  @Test
  public void testRecentPassword(final TestContext context) throws InterruptedException {
    RestAssured.given()
      .spec(spec)
      .header(RestVerticle.OKAPI_USERID_HEADER, userId)
      .body(new JsonObject().put(PASSWORD_JSON_PATH, PASSWORD_TEMPLATE + new Random().nextInt(PASSWORD_COUNT)).encode())
      .when()
      .post(PASSWORD_REAPITABILITY_VALIDATION_PATH)
      .then()
      .statusCode(200)
      .body(RESULT_JSON_PATH, is(INVALID));
  }

  @Test
  public void testOldPassword(final TestContext context) throws InterruptedException {
    RestAssured.given()
      .spec(spec)
      .header(RestVerticle.OKAPI_USERID_HEADER, userId)
      .body(new JsonObject().put(PASSWORD_JSON_PATH, OLD_PASSWORD).encode())
      .when()
      .post(PASSWORD_REAPITABILITY_VALIDATION_PATH)
      .then()
      .statusCode(200)
      .body(RESULT_JSON_PATH, is(VALID));
  }

  private static void fillInCredentialsHistory(Async async) {
    //add ten passwords
    List<Future> list = IntStream.range(0, PASSWORD_COUNT)
      .mapToObj(i -> PASSWORD_TEMPLATE + i)
      .map(PasswordRepeatabilityValidationTest::buildCredentialsHistoryObject)
      .map(PasswordRepeatabilityValidationTest::saveCredentialsHistoryObject)
      .collect(Collectors.toList());

    //add older password
    long hourMillis = 1000 * 60 * 60;
    list.add(saveCredentialsHistoryObject(buildCredentialsHistoryObject(
      OLD_PASSWORD, new Date(new Date().getTime() - hourMillis))));

    CompositeFuture.all(list).setHandler(event -> {
      if (event.succeeded()) {
        async.complete();
      }
    });
  }

  private static Future saveCredentialsHistoryObject(CredentialsHistory obj) {
    Future future = Future.future();

    PostgresClient client = PostgresClient.getInstance(vertx, TENANT);
    client.save(TABLE_NAME_CREDENTIALS_HISTORY, UUID.randomUUID().toString(), obj, event -> {
      if (event.failed()) {
        future.fail(event.cause());
      } else {
        future.complete();
      }
    });

    return future;
  }

  private static CredentialsHistory buildCredentialsHistoryObject(String password) {
    return buildCredentialsHistoryObject(password, new Date());
  }

  private static CredentialsHistory buildCredentialsHistoryObject(String password, Date date) {
    CredentialsHistory history = new CredentialsHistory();
    String salt = authUtil.getSalt();
    history.setId(UUID.randomUUID().toString());
    history.setUserId(userId);
    history.setHash(authUtil.calculateHash(password, salt));
    history.setSalt(salt);
    history.setDate(date);

    return history;
  }
}
