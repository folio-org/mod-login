package org.folio.logintest;

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
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.http.HttpStatus;
import org.folio.rest.RestVerticle;
import org.folio.rest.client.TenantClient;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.folio.util.AuthUtil;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import org.folio.rest.jaxrs.model.TenantAttributes;

@RunWith(VertxUnitRunner.class)
public class CredentialExistenceTest {

  private static final String USER_ID_PARAM = "userId";
  private static Vertx vertx;
  private static RequestSpecification spec;
  private static AuthUtil authUtil = new AuthUtil();

  private static final String EXISTING_CREDENTIALS_USER_ID = "e341d8bb-5d5d-4ce8-808c-b2e9bbfb4a1a";
  private static final String NOT_EXISTING_CREDENTIALS_USER_ID = "6b1492f0-9c6f-4d51-bfdc-6c7fc53a80f3";

  private static final String TENANT = "diku";
  private static final String TABLE_NAME_CREDENTIALS = "auth_credentials";
  private static final String CREDENTIALS_EXISTENCE_PATH = "/authn/credentials-existence";
  private static final String CREDENTIALS_EXIST = "credentialsExist";

  @BeforeClass
  public static void setup(final TestContext context) {
    Async async = context.async();
    vertx = Vertx.vertx();
    int port = NetworkUtils.nextFreePort();
    String okapiUrl = "http://localhost:" + port;

    try {
      PostgresClient.setIsEmbedded(true);
      PostgresClient.getInstance(vertx).startEmbeddedPostgres();
    } catch (Exception e) {
      context.fail(e);
    }

    TenantClient tenantClient = new TenantClient(okapiUrl, TENANT, "token");
    DeploymentOptions restVerticleDeploymentOptions =
      new DeploymentOptions().setConfig(new JsonObject().put("http.port", port));
    vertx.deployVerticle(RestVerticle.class.getName(), restVerticleDeploymentOptions, res -> {
      try {
        TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
        tenantClient.postTenant(ta, handler -> {
          saveCredential(EXISTING_CREDENTIALS_USER_ID, "password")
            .onComplete(context.asyncAssertSuccess(done -> async.complete()));
        });
      } catch (Exception e) {
        context.fail(e);
      }
    });

    spec = new RequestSpecBuilder()
      .setContentType(ContentType.JSON)
      .setBaseUri(okapiUrl)
      .addHeader(RestVerticle.OKAPI_HEADER_TENANT, TENANT)
      .addHeader(RestVerticle.OKAPI_HEADER_TOKEN, "dummytoken")
      .addHeader(LoginAPI.OKAPI_URL_HEADER, okapiUrl)
      .build();
  }

  @AfterClass
  public static void teardown(TestContext context) {
    PostgresClient.getInstance(vertx, TENANT).delete(TABLE_NAME_CREDENTIALS, new Criterion(), event -> {
      if (event.failed()) {
        context.fail(event.cause());
      }
    });

    vertx.close(context.asyncAssertSuccess(res -> {
      PostgresClient.stopEmbeddedPostgres();
    }));
  }

  @Test
  public void shouldReturnTrueWhenCredentialForUserExists() {
    RestAssured.given()
      .spec(spec)
      .param(USER_ID_PARAM, EXISTING_CREDENTIALS_USER_ID)
      .when()
      .get(CREDENTIALS_EXISTENCE_PATH)
      .then()
      .statusCode(HttpStatus.SC_OK)
      .body(CREDENTIALS_EXIST, Matchers.is(true));
  }

  @Test
  public void shouldReturnFalseWhenCredentialForUserDoesNotExist() {
    RestAssured.given()
      .spec(spec)
      .param(USER_ID_PARAM, NOT_EXISTING_CREDENTIALS_USER_ID)
      .when()
      .get(CREDENTIALS_EXISTENCE_PATH)
      .then()
      .statusCode(HttpStatus.SC_OK)
      .body(CREDENTIALS_EXIST, Matchers.is(false));
  }

  private static Future<Void> saveCredential(String userId, String password) {
    Promise<String> promise = Promise.promise();
    String salt = authUtil.getSalt();
    Credential credential = new Credential();
    credential.setId(UUID.randomUUID().toString());
    credential.setSalt(salt);
    credential.setHash(authUtil.calculateHash(password, salt));
    credential.setUserId(userId);
    PostgresClient.getInstance(vertx, TENANT)
      .save(TABLE_NAME_CREDENTIALS, UUID.randomUUID().toString(), credential, promise);
    return promise.future().map(s -> null);
  }
}
