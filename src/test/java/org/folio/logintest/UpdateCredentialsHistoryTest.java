package org.folio.logintest;

import static org.folio.logintest.UserMock.gollumId;
import static org.folio.services.impl.PasswordStorageServiceImpl.DEFAULT_PASSWORDS_HISTORY_NUMBER;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;

import org.apache.http.HttpStatus;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.RestVerticle;
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
      .addHeader(XOkapiHeaders.TENANT, TENANT)
      .addHeader(XOkapiHeaders.TOKEN, TOKEN)
      .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
      .addHeader(XOkapiHeaders.USER_ID, gollumId)
      .addHeader(XOkapiHeaders.REQUEST_TIMESTAMP, String.valueOf(new Date().getTime()))
      .build();

    try {
      PostgresClient.setPostgresTester(new PostgresTesterContainer());
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
    promise.future().onComplete(context.asyncAssertSuccess(results -> {
      List<CredentialsHistory> credHist = results.getResults();
      context.assertEquals(credHist.size(), 1);

      CredentialsHistory hisObject = credHist.get(0);
      context.assertEquals(hisObject.getHash(), authUtil.calculateHash(INITIAL_PASSWORD, hisObject.getSalt()));
    }));
  }

  @Test
  public void testInitialPasswordIsDeletedWhenHistoryOverflowsWithDefaultPwdHistNumber(TestContext context) {
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

    isInitialPasswordRemovedFromHistory()
        .onComplete(context.asyncAssertSuccess(any -> context.assertFalse(any)));
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
    TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
    return TestUtil.postSync(ta, TENANT, port, vertx);
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
