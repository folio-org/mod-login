package org.folio.logintest;


import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasKey;

import org.folio.okapi.common.XOkapiHeaders;
import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.RestVerticle;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.matcher.RestAssuredMatchers;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;

@RunWith(VertxUnitRunner.class)
public class LogoutTest {

  private static Vertx vertx;
  private static RequestSpecification spec;
  private static RequestSpecification specExpiredToken;

  private static final String TENANT_DIKU = "diku";
  private static final String LOGOUT_PATH = "/authn/logout";
  private static final String LOGOUT_ALL_PATH = "/authn/logout-all";

  @BeforeClass
  public static void setup(final TestContext context) throws Exception {
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

    spec = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader(XOkapiHeaders.TOKEN, "dummy.token")
        .build();

    specExpiredToken = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader(XOkapiHeaders.TOKEN, "expiredtoken")
        .build();

    TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
    vertx.deployVerticle(Mocks.class.getName(), mockOptions)
        .compose(res -> vertx.deployVerticle(RestVerticle.class.getName(), options))
        .compose(res -> TestUtil.postSync(ta, TENANT_DIKU, port, vertx))
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void testLogout(final TestContext context) {
    RestAssured.given()
      .spec(spec)
      .when()
      .delete(LOGOUT_PATH)
      .then()
      .log().all()
      .statusCode(200)
      .cookie("refreshToken", "")
      .cookie("accessToken", "");
  }

  @Test
  public void testLogoutBadRequest(final TestContext context) {
    RestAssured.given()
      .spec(specExpiredToken)
      .when()
      .delete(LOGOUT_PATH)
      .then()
      .log().all()
      .statusCode(422);
  }

  @Test
  public void testLogoutAll(final TestContext context) {
    RestAssured.given()
      .spec(spec)
      .when()
      .delete(LOGOUT_ALL_PATH)
      .then()
      .log().all()
      .statusCode(200)
      .cookie("refreshToken", "")
      .cookie("accessToken", "");
  }

  @Test
  public void testLogoutBadRequestAll(final TestContext context) {
    RestAssured.given()
      .spec(specExpiredToken)
      .when()
      .delete(LOGOUT_ALL_PATH)
      .then()
      .log().all()
      .statusCode(422);
  }
}
