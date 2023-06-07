package org.folio.logintest;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.hamcrest.Matchers.equalTo;

import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.folio.okapi.common.XOkapiHeaders;
import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.rest.persist.PostgresClient;
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
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;

@RunWith(VertxUnitRunner.class)
public class CrossTenantLoginTest {

  private static Vertx vertx;
  private static RequestSpecification spec;
  private static RequestSpecification specWithOtherTenant;

  private static final String TENANT_DIKU = "diku";
  private static final String TENANT_OTHER = "other";
  private static final String CRED_PATH = "/authn/credentials";
  private static final String LOGIN_PATH = "/authn/login";
  private static final String adminId = "8bd684c1-bbc3-4cf1-bcf4-8013d02a94ce";

  final private JsonObject credsWithTenant1 = new JsonObject()
    .put("username", "admin")
    .put("password", "admin1")
    .put("tenant", TENANT_OTHER);

  final private JsonObject credsWithTenant2 = new JsonObject()
    .put("username", "missing")
    .put("password", "admin1")
    .put("tenant", TENANT_OTHER);

  final private JsonObject credsWithTenant3 = new JsonObject()
    .put("username", "admin")
    .put("password", "admin1")
    .put("tenant", "missing");

  final private JsonObject credsWithTenant4 = new JsonObject()
    .put("userId", adminId)
    .put("password", "admin1")
    .put("tenant", TENANT_OTHER);

  final private JsonObject credsWithoutTenant1 = new JsonObject()
    .put("username", "single")
    .put("password", "secret");

  final private JsonObject credsWithoutTenant2 = new JsonObject()
    .put("username", "multiple")
    .put("password", "secret");

  private static Map<String, String> moduleArgs;

  @BeforeClass
  public static void setup(final TestContext context) {
    moduleArgs = new HashMap<>(MODULE_SPECIFIC_ARGS);
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

    PostgresClient.setPostgresTester(new PostgresTesterContainer());
    PostgresClient.getInstance(vertx);

    spec = initSpec(TENANT_DIKU, port, mockPort);
    specWithOtherTenant = initSpec(TENANT_OTHER, port, mockPort);

    TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
    vertx.deployVerticle(UserMock.class.getName(), mockOptions)
      .compose(res -> vertx.deployVerticle(RestVerticle.class.getName(), options))
      .compose(res -> TestUtil.postSync(ta, TENANT_DIKU, port, vertx))
      .compose(res -> TestUtil.postSync(ta, TENANT_OTHER, port, vertx))
      .onComplete(context.asyncAssertSuccess());
  }

  private static RequestSpecification initSpec(String tenant, int port, int mockPort) {
    return new RequestSpecBuilder()
      .setContentType(ContentType.JSON)
      .setBaseUri("http://localhost:" + port)
      .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
      .addHeader(XOkapiHeaders.TOKEN, "dummy.token")
      .addHeader(XOkapiHeaders.REQUEST_TIMESTAMP, String.valueOf(new Date().getTime()))
      .addHeader(XOkapiHeaders.TENANT, tenant)
      .build();
  }

  @Before
  public void setUp(TestContext context) {
    MODULE_SPECIFIC_ARGS.clear();
    MODULE_SPECIFIC_ARGS.putAll(moduleArgs);
    UserMock.resetConfigs();
    dBCleanupWithTenant(context, TENANT_DIKU);
    dBCleanupWithTenant(context, TENANT_OTHER);
  }

  private void dBCleanupWithTenant(TestContext context, String tenant) {
    PostgresClient.getInstance(vertx, tenant)
      .execute("TRUNCATE auth_attempts, auth_credentials, auth_credentials_history")
      .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void test() throws UnsupportedEncodingException {
    RestAssured.given()
      .spec(specWithOtherTenant)
      .body(credsWithTenant1.encode())
      .when()
      .post(CRED_PATH)
      .then()
      .log().all()
      .statusCode(201);

    RestAssured.given()
      .spec(spec)
      .body(credsWithTenant1.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(201);

    RestAssured.given()
      .spec(spec)
      .body(credsWithTenant2.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(422)
      .body("errors[0].code", equalTo("username.incorrect"));

    RestAssured.given()
      .spec(spec)
      .body(credsWithTenant4.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(201);

    RestAssured.given()
      .spec(specWithOtherTenant)
      .body(credsWithoutTenant1.encode())
      .when()
      .post(CRED_PATH)
      .then()
      .log().all()
      .statusCode(201);

    RestAssured.given()
      .spec(spec)
      .body(credsWithoutTenant1.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(201);

    RestAssured.given()
      .spec(spec)
      .body(credsWithoutTenant2.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(422)
      .body("errors[0].code", equalTo("bad.credentials"));
  }

}
