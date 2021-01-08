package org.folio.logintest;

import static org.folio.logintest.UserMock.gollumId;
import static org.folio.services.impl.PasswordStorageServiceImpl.DEFAULT_PASSWORDS_HISTORY_NUMBER;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;

import org.apache.http.HttpStatus;
import org.folio.rest.RestVerticle;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.impl.TenantAPI;
import org.folio.rest.impl.TenantRefAPI;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.CredentialsHistory;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.rest.jaxrs.model.UpdateCredentials;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.interfaces.Results;
import org.folio.rest.tools.utils.NetworkUtils;
import org.folio.util.AuthUtil;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;

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
    .setOperation("=")
    .setVal(gollumId);

  @BeforeClass
  public static void setUp(TestContext context) {
    vertx = Vertx.vertx();
    port = NetworkUtils.nextFreePort();
    mockPort = NetworkUtils.nextFreePort();

    spec = new RequestSpecBuilder()
      .setBaseUri("http://localhost:" + port)
      .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
      .addHeader(RestVerticle.OKAPI_HEADER_TENANT, TENANT)
      .addHeader(RestVerticle.OKAPI_HEADER_TOKEN, TOKEN)
      .addHeader(LoginAPI.OKAPI_URL_HEADER, "http://localhost:" + mockPort)
      .addHeader(LoginAPI.OKAPI_USER_ID_HEADER, gollumId)
      .addHeader(LoginAPI.OKAPI_REQUEST_TIMESTAMP_HEADER, String.valueOf(new Date().getTime()))
      .build();

    try {
      PostgresClient.setIsEmbedded(true);
      PostgresClient.getInstance(vertx);
    } catch (Exception e) {
      context.fail(e);
    }

    pgClient = PostgresClient.getInstance(vertx, TENANT);

    Future.succeededFuture()
      .compose(v -> deployUserMockVerticle())
      .compose(v -> deployRestVerticle())
      .compose(v -> postTenant())
      .compose(v -> persistCredentials())
      .onComplete(context.asyncAssertSuccess());

  }

  @AfterClass
  public static void tearDown(TestContext context) {
    PostgresClient.stopEmbeddedPostgres();
    vertx.close(context.asyncAssertSuccess());
  }

  @After
  public void cleanUp(TestContext context) {

    Future.succeededFuture()
      .compose(v -> clearCredentialsHistoryTable())
      .compose(v -> clearCredentialsTable())
      .compose(v -> persistCredentials())
      .onComplete(context.asyncAssertSuccess());
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

    Promise<Results<CredentialsHistory>> promise = Promise.promise();
    pgClient.get(TABLE_NAME_CRED_HIST, CredentialsHistory.class, new Criterion(userIdCrit), false, promise);
    promise.future().onComplete(results -> {
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
      .withUserId(gollumId);
    String password = INITIAL_PASSWORD;
    String newPassword;

    for (int i = 0; i < DEFAULT_PASSWORDS_HISTORY_NUMBER; i++) {
      newPassword = PASSWORD_TEMPLATE + i;

      RestAssured.given()
        .spec(spec)
        .body(JsonObject.mapFrom(entity
          .withPassword(password)
          .withNewPassword(newPassword)).toString())
        .when()
        .post("/authn/update")
        .then()
        .statusCode(HttpStatus.SC_NO_CONTENT);

      password = newPassword;
    }

    isInitialPasswordRemovedFromHistory().onComplete(any -> {
      if (any.failed()) {
        context.fail(any.cause());
      } else {
        context.assertFalse(any.result());
        async.complete();
      }
    });
  }

  private Future<Boolean> isInitialPasswordRemovedFromHistory() {
    Promise<Boolean> promise = Promise.promise();

    pgClient.get(TABLE_NAME_CRED_HIST, CredentialsHistory.class, new Criterion(userIdCrit), false, get -> {
      if (get.failed()) {
        promise.fail(get.cause());
      } else {
        boolean any = get.result().getResults()
          .stream()
          .anyMatch(obj -> authUtil.calculateHash(INITIAL_PASSWORD, obj.getSalt()).equals(obj.getHash()));

        promise.complete(any);
      }
    });

    return promise.future();
  }

  private static Future<Void> postTenant() {
    Promise<Void> promise = Promise.promise();
    TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
    TenantAPI tenantAPI = new TenantRefAPI();
    Map<String, String> okapiHeaders = Map.of("x-okapi-url", "http://localhost:" + port,
        "x-okapi-tenant", TENANT);
    tenantAPI.postTenantSync(ta, okapiHeaders, handler -> promise.complete(),
        vertx.getOrCreateContext());
    return promise.future();
  }

  private static Future<Void> deployUserMockVerticle() {
    DeploymentOptions options = new DeploymentOptions().setConfig(
      new JsonObject().put("port", mockPort));
    return vertx.deployVerticle(UserMock.class, options).mapEmpty();
  }

  private static Future<Void> deployRestVerticle() {
    DeploymentOptions options = new DeploymentOptions().setConfig(
      new JsonObject().put("http.port", port));
    return vertx.deployVerticle(RestVerticle.class, options).mapEmpty();
  }

  private static Future<Void> persistCredentials() {
    Promise<String> promise = Promise.promise();
    authUtil = new AuthUtil();
    String salt = authUtil.getSalt();
    String id = UUID.randomUUID().toString();
    Credential cred = new Credential()
      .withId(id)
      .withSalt(salt)
      .withHash(authUtil.calculateHash(INITIAL_PASSWORD, salt))
      .withUserId(gollumId);
    pgClient.save(TABLE_NAME_CRED, id, cred, promise);
    return promise.future().map(s -> null);
  }

  private Future<Void> clearCredentialsHistoryTable() {
    Promise<RowSet<Row>> promise = Promise.promise();
    pgClient.delete(TABLE_NAME_CRED_HIST, new Criterion(userIdCrit), promise);
    return promise.future().map(s -> null);
  }

  private Future<Void> clearCredentialsTable() {
    Promise<RowSet<Row>> promise = Promise.promise();
    pgClient.delete(TABLE_NAME_CRED, new Criterion(userIdCrit), promise);
    return promise.future().map(s -> null);
  }

}
