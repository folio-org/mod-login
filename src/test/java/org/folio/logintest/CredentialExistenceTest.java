package org.folio.logintest;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.http.HttpStatus;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.folio.util.AuthUtil;
import org.hamcrest.Matchers;
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
    vertx = Vertx.vertx();
    int port = NetworkUtils.nextFreePort();
    String okapiUrl = "http://localhost:" + port;

    PostgresClient.setPostgresTester(new PostgresTesterContainer());
    PostgresClient.getInstance(vertx);

    spec = new RequestSpecBuilder()
        .setContentType(ContentType.JSON)
        .setBaseUri(okapiUrl)
        .addHeader(XOkapiHeaders.TENANT, TENANT)
        .addHeader(XOkapiHeaders.TOKEN, "dummytoken")
        .addHeader(XOkapiHeaders.URL, okapiUrl)
        .build();

    DeploymentOptions restVerticleDeploymentOptions =
        new DeploymentOptions().setConfig(new JsonObject().put("http.port", port));
    TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
    vertx.deployVerticle(RestVerticle.class.getName(), restVerticleDeploymentOptions)
        .compose(x -> TestUtil.postSync(ta, TENANT, port, vertx))
        .compose(x -> saveCredential(EXISTING_CREDENTIALS_USER_ID, "password"))
        .onComplete(context.asyncAssertSuccess());
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
    String salt = authUtil.getSalt();
    Credential credential = new Credential();
    credential.setId(UUID.randomUUID().toString());
    credential.setSalt(salt);
    credential.setHash(authUtil.calculateHash(password, salt));
    credential.setUserId(userId);
    return PostgresClient.getInstance(vertx, TENANT)
      .save(TABLE_NAME_CREDENTIALS, UUID.randomUUID().toString(), credential).mapEmpty();
  }
}
