package org.folio.logintest;

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

import io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite;
import io.restassured.RestAssured;
import io.restassured.matcher.RestAssuredMatchers;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;

import java.util.Date;

@RunWith(VertxUnitRunner.class)
public class LogoutTest {

  private static Vertx vertx;
  private static RequestSpecification specLogout;
  private static RequestSpecification specLogoutExpiredToken;
  private static RequestSpecification specLogoutAll;
  private static RequestSpecification specLogoutAllExpiredToken;

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

    var cookieHeader = LoginAPI.FOLIO_REFRESH_TOKEN + "=123;" + LoginAPI.FOLIO_ACCESS_TOKEN + "=321";
    var cookieHeaderExpired = LoginAPI.FOLIO_REFRESH_TOKEN + "=expiredToken;" + LoginAPI.FOLIO_ACCESS_TOKEN + "=321";

    specLogout = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader(XOkapiHeaders.TOKEN, "123")
        .addHeader("Cookie", cookieHeader)
        .build();

    specLogoutExpiredToken = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader(XOkapiHeaders.TOKEN, "expiredtoken")
        .addHeader("Cookie", cookieHeaderExpired)
        .build();

    specLogoutAll = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader(XOkapiHeaders.TOKEN, "123")
        .build();

    specLogoutAllExpiredToken = new RequestSpecBuilder()
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
      testLogoutCookieResponse(specLogout, LOGOUT_PATH);
    }

  @Test
  public void testLogoutBadRequest(final TestContext context) {
    RestAssured.given()
      .spec(specLogoutExpiredToken)
      .when()
      .post(LOGOUT_PATH)
      .then()
      .log().all()
      .statusCode(422);
  }

  @Test
  public void testLogoutAll(final TestContext context) {
    testLogoutCookieResponse(specLogoutAll, LOGOUT_ALL_PATH);
  }

  @Test
  public void testLogoutBadRequestAll(final TestContext context) {
    RestAssured.given()
      .spec(specLogoutAllExpiredToken)
      .when()
      .post(LOGOUT_ALL_PATH)
      .then()
      .log().all()
      .statusCode(422);
  }

  private void testLogoutCookieResponse(RequestSpecification spec, String path) {
    RestAssured.given()
        .spec(spec)
        .when()
        .post(path)
        .then()
        .log().all()
        .statusCode(204)
        .cookie(LoginAPI.FOLIO_REFRESH_TOKEN, RestAssuredMatchers.detailedCookie()
            .value("")
            .expiryDate(new Date(0))
            .sameSite("None")
            .secured(true)
            .path("/authn"))
        .cookie(LoginAPI.FOLIO_ACCESS_TOKEN, RestAssuredMatchers.detailedCookie()
            .value("")
            .path("/")
            .secured(true)
            .sameSite("None")
            .expiryDate(new Date(0)));
  }
}
