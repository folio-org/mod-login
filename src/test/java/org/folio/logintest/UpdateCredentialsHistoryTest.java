package org.folio.logintest;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.CompositeFuture;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.http.HttpStatus;
import org.folio.rest.RestVerticle;
import org.folio.rest.client.TenantClient;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.CredentialsHistory;
import org.folio.rest.jaxrs.model.UpdateCredentials;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.interfaces.Results;
import org.folio.rest.tools.utils.NetworkUtils;
import org.folio.util.AuthUtil;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.folio.logintest.UserMock.gollumId;
import static org.folio.services.impl.PasswordStorageServiceImpl.DEFAULT_PASSWORDS_HISTORY_NUMBER;

@RunWith(VertxUnitRunner.class)
public class UpdateCredentialsHistoryTest {

  private static Vertx vertx;
  private static PostgresClient pgClient;
  private static RequestSpecification spec;
  private static int port;
  private static int mockPort;

  private static final String TABLE_NAME_CRED = "auth_credentials";
  private static final String TABLE_NAME_CRED_HIST = "auth_credentials_history";
  private static final String TENANT = "test";
  private static final String TOKEN = "header.payload.signature";
  private static final String INITIAL_PASSWORD = "Init12!@";
  private static final String NEW_PASSWORD = "Newpwd12!@";
  private static final String PASSWORD_TEMPLATE = "Admin!@1";

  private static AuthUtil authUtil = new AuthUtil();

  private Criteria userIdCrit = new Criteria()
    .addField("'userId'")
    .setOperation(Criteria.OP_EQUAL)
    .setValue(gollumId);

  @BeforeClass
  public static void setUp(TestContext context) {
    vertx = Vertx.vertx();
    pgClient = PostgresClient.getInstance(vertx, TENANT);
    port = NetworkUtils.nextFreePort();
    mockPort = NetworkUtils.nextFreePort();

    spec = new RequestSpecBuilder()
      .setBaseUri("http://localhost:" + port)
      .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
      .addHeader(RestVerticle.OKAPI_HEADER_TENANT, TENANT)
      .addHeader(RestVerticle.OKAPI_HEADER_TOKEN, TOKEN)
      .addHeader(LoginAPI.OKAPI_URL_HEADER, "http://localhost:" + mockPort)
      .addHeader(LoginAPI.OKAPI_USER_ID_HEADER, gollumId)
      .build();

    try {
      PostgresClient.setIsEmbedded(true);
      PostgresClient.getInstance(vertx).startEmbeddedPostgres();
    } catch (Exception e) {
      context.fail(e);
    }

    Future.succeededFuture()
      .compose(v -> deployUserMockVerticle())
      .compose(v -> deployRestVerticle())
      .compose(v -> postTenant())
      .compose(v -> persistCredentials())
      .setHandler(context.asyncAssertSuccess());

  }

  @AfterClass
  public static void tearDown(TestContext context) {
    PostgresClient.stopEmbeddedPostgres();
    vertx.close(context.asyncAssertSuccess());
  }

  @After
  public void cleanUp(TestContext context) {
    Async async = context.async();

    pgClient.delete(TABLE_NAME_CRED_HIST, new Criterion(userIdCrit), del -> {
      if (del.failed()) {
        context.fail(del.cause());
      } else {
        async.complete();
      }
    });
  }

  @Test
  public void testInitialPasswordIsSavedToHistory(TestContext context) {
    Async async = context.async();

    UpdateCredentials entity = new UpdateCredentials()
      .withUserId(gollumId)
      .withPassword(INITIAL_PASSWORD)
      .withNewPassword(NEW_PASSWORD);

    RestAssured.given()
      .spec(spec)
      .body(JsonObject.mapFrom(entity).toString())
      .when()
      .post("/authn/update")
      .then()
      .statusCode(HttpStatus.SC_NO_CONTENT);

    Future<Results<CredentialsHistory>> future = Future.future();
    pgClient.get(TABLE_NAME_CRED_HIST, CredentialsHistory.class, new Criterion(userIdCrit), false, future.completer());
    future.setHandler(results -> {
      List<CredentialsHistory> credHist = results.result().getResults();
      context.assertEquals(credHist.size(), 1);

      CredentialsHistory hisObject = credHist.get(0);
      context.assertEquals(hisObject.getHash(), authUtil.calculateHash(INITIAL_PASSWORD, hisObject.getSalt()));

      async.complete();
    });
  }

  @Test
  public void testInitialPasswordIsDeletedWhenHistoryOverflowsWithDefaultPwdHistNumber(TestContext context) {
    Async async = context.async();

    UpdateCredentials entity = new UpdateCredentials()
      .withUserId(gollumId)
      .withPassword(INITIAL_PASSWORD)
      .withNewPassword(NEW_PASSWORD);

    RestAssured.given()
      .spec(spec)
      .body(JsonObject.mapFrom(entity).toString())
      .when()
      .post("/authn/update")
      .then()
      .statusCode(HttpStatus.SC_NO_CONTENT);

    //add more records to make cred history full
    fillInCredentialsHistory()
      .setHandler(v -> {
        RestAssured.given()
          .spec(spec)
          .body(JsonObject.mapFrom(entity
            .withPassword(NEW_PASSWORD)
            .withNewPassword(PASSWORD_TEMPLATE + (DEFAULT_PASSWORDS_HISTORY_NUMBER - 1))).toString())
          .when()
          .post("/authn/update")
          .then()
          .statusCode(HttpStatus.SC_NO_CONTENT);

        isInitialPasswordRemovedFromHistory().setHandler(any -> {
          if (any.failed()) {
            context.fail(any.cause());
          } else {
            context.assertFalse(any.result());
            async.complete();
          }
        });
      });

  }

  private Future<Boolean> isInitialPasswordRemovedFromHistory() {
    Future<Boolean> future = Future.future();

    pgClient.get(TABLE_NAME_CRED_HIST, CredentialsHistory.class, new Criterion(userIdCrit), false, get -> {
      if (get.failed()) {
        future.fail(get.cause());
      } else {
        boolean any = get.result().getResults()
          .stream()
          .anyMatch(obj -> authUtil.calculateHash(INITIAL_PASSWORD, obj.getSalt()).equals(obj.getHash()));

        future.complete(any);
      }
    });

    return future;
  }

  private Future<Void> fillInCredentialsHistory() {

    return CompositeFuture.all(IntStream.range(0, DEFAULT_PASSWORDS_HISTORY_NUMBER - 1)
      .mapToObj(UpdateCredentialsHistoryTest::buildCredentialsHistoryObject)
      .map(UpdateCredentialsHistoryTest::saveCredentialsHistoryObject)
      .collect(Collectors.toList()))
      .map(v -> null);
  }

  private static Future<Void> postTenant() {
    Future<Void> future = Future.future();
    try {
      new TenantClient("http://localhost:" + port, TENANT, TOKEN, false)
        .postTenant(null, resp -> {
          if (resp.statusCode() != HttpStatus.SC_CREATED) {
            future.fail(resp.statusMessage());
          }
          future.complete();
        });
    } catch (Exception e) {
      future.fail(e);
    }
    return future;
  }

  private static Future<Void> deployUserMockVerticle() {
    Future<String> future = Future.future();
    DeploymentOptions options = new DeploymentOptions().setConfig(
      new JsonObject().put("port", mockPort));
    vertx.deployVerticle(UserMock.class, options, future.completer());

    return future.map(v -> null);
  }

  private static Future<Void> deployRestVerticle() {
    Future<String> future = Future.future();
    DeploymentOptions options = new DeploymentOptions().setConfig(
      new JsonObject().put("http.port", port));
    vertx.deployVerticle(RestVerticle.class, options, future.completer());

    return future.map(v -> null);
  }

  private static Future<Void> persistCredentials() {
    Future<String> future = Future.future();
    authUtil = new AuthUtil();
    String salt = authUtil.getSalt();
    String id = UUID.randomUUID().toString();
    Credential cred = new Credential()
      .withId(id)
      .withSalt(salt)
      .withHash(authUtil.calculateHash(INITIAL_PASSWORD, salt))
      .withUserId(gollumId);
    pgClient.save(TABLE_NAME_CRED, id, cred, future.completer());

    return future.map(v -> null);
  }

  private static Future<Void> saveCredentialsHistoryObject(CredentialsHistory object) {
    Future<String> future = Future.future();
    pgClient.save(TABLE_NAME_CRED_HIST, UUID.randomUUID().toString(), object, future.completer());

    return future.map(v -> null);
  }

  private static CredentialsHistory buildCredentialsHistoryObject(int i) {
    CredentialsHistory history = new CredentialsHistory();
    String salt = authUtil.getSalt();
    history.setId(UUID.randomUUID().toString());
    history.setUserId(gollumId);
    history.setHash(authUtil.calculateHash(PASSWORD_TEMPLATE + i, salt));
    history.setSalt(salt);
    history.setDate(new Date());

    return history;
  }
}
