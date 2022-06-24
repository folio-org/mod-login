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
  private static RequestSpecification specBadRequestMissingAccessTokenCookie;
  private static RequestSpecification specBadRequestNoCookie;
  private static RequestSpecification specBadRequestEmptyCookie;

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
        .addHeader("Cookie", "accessToken=123")
        .build();

    specExpiredToken = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader("Cookie", "accessToken=expiredtoken")
        .build();

    specBadRequestMissingAccessTokenCookie = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader("Cookie", "refreshToken=321")
        .build();

    specBadRequestEmptyCookie = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader("Cookie", "")
        .build();

    specBadRequestNoCookie = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
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
      .statusCode(201)
      .contentType("application/json")
      .cookie("refreshToken", RestAssuredMatchers.detailedCookie()
          .value("")
          .path("/authn/refresh")
          .httpOnly(true)
          .secured(true))
      .cookie("accessToken", RestAssuredMatchers.detailedCookie()
          .value("")
          .httpOnly(true)
          .secured(true));
  }

  @Test
  public void testLogoutUnprocessable(final TestContext context) {
    RestAssured.given()
      .spec(specExpiredToken)
      .when()
      .delete(LOGOUT_PATH)
      .then()
      .log().all()
      .statusCode(422)
      .body("errors[0].code", is(LoginAPI.TOKEN_LOGOUT_UNPROCESSABLE_CODE))
      .body("errors[0].message", is(LoginAPI.TOKEN_LOGOUT_UNPROCESSABLE));
  }

  @Test
  public void testLogoutBadRequestEmptyCookie(final TestContext context) {
    RestAssured.given()
      .spec(specBadRequestEmptyCookie)
      .when()
      .delete(LOGOUT_PATH)
      .then()
      .log().all()
      .statusCode(400)
      .body("errors[0].code", is(LoginAPI.TOKEN_PARSE_BAD_CODE))
      .body("errors[0].message", is(LoginAPI.BAD_REQUEST));
  }

  @Test
  public void testLogoutBadRequestNoCookie(final TestContext context) {
    RestAssured.given()
      .spec(specBadRequestNoCookie)
      .when()
      .delete(LOGOUT_PATH)
      .then()
      .log().all()
      .statusCode(400)
      .body("errors[0].code", is(LoginAPI.TOKEN_PARSE_BAD_CODE))
      .body("errors[0].message", is(LoginAPI.BAD_REQUEST));
  }

  @Test
  public void testLogoutMissingAccessTokenCookie(final TestContext context) {
    RestAssured.given()
      .spec(specBadRequestMissingAccessTokenCookie)
      .when()
      .delete(LOGOUT_PATH)
      .then()
      .log().all()
      .statusCode(400)
      .body("errors[0].code", is(LoginAPI.TOKEN_PARSE_BAD_CODE))
      .body("errors[0].message", is(LoginAPI.BAD_REQUEST));
  }
}