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
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.CredentialsHistory;
import org.folio.rest.jaxrs.model.Password;
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
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.folio.rest.jaxrs.model.TenantAttributes;

import static org.hamcrest.Matchers.is;


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
        TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
        tenantClient.postTenant(ta, handler -> fillInCredentialsHistory()
          .compose(v -> saveCredential())
          .setHandler(v -> {
            if (v.succeeded()) {
              async.complete();
            }
          }));
      } catch (Exception e) {
        context.fail(e);
      }
    });

    spec = new RequestSpecBuilder()
      .setContentType(ContentType.JSON)
      .setBaseUri("http://localhost:" + port)
      .addHeader(RestVerticle.OKAPI_HEADER_TENANT, TENANT)
      .addHeader(RestVerticle.OKAPI_HEADER_TOKEN, "dummytoken")
      .addHeader(LoginAPI.OKAPI_URL_HEADER, "http://localhost:" + port)
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
      .header(RestVerticle.OKAPI_USERID_HEADER, USER_ID)
      .body(buildPasswordEntity(CURRENT_PASSWORD))
      .when()
      .post(PASSWORD_REAPITABILITY_VALIDATION_PATH)
      .then()
      .statusCode(200)
      .body(RESULT_JSON_PATH, is(INVALID));
  }

  private static Future<Void> fillInCredentialsHistory() {
    Future<Void> future = Future.future();

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

    CompositeFuture.all(list).setHandler(event -> {
      if (event.succeeded()) {
        future.complete();
      } else {
        future.fail(event.cause());
      }
    });

    return future;
  }

  private static Future<Void> saveCredential() {
    Future<String> future = Future.future();
    String salt = authUtil.getSalt();
    Credential credential = new Credential();
    credential.setId(UUID.randomUUID().toString());
    credential.setSalt(salt);
    credential.setHash(authUtil.calculateHash(CURRENT_PASSWORD, salt));
    credential.setUserId(USER_ID);
    PostgresClient.getInstance(vertx, TENANT).save(TABLE_NAME_CREDENTIALS, UUID.randomUUID().toString(),
      credential, future.completer());
    return future.map(s -> null);
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
    history.setUserId(USER_ID);
    history.setHash(authUtil.calculateHash(password, salt));
    history.setSalt(salt);
    history.setDate(date);

    return history;
  }

  private Password buildPasswordEntity(String password) {
    return new Password()
      .withPassword(password)
      .withUserId(USER_ID);
  }
}
