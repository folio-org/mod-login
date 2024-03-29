package org.folio.logintest;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;

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
public class RefreshTest {

  private static Vertx vertx;
  private static RequestSpecification specWithBothAccessAndRefreshTokenCookie;
  private static RequestSpecification specWithoutAccessTokenCookie;
  private static RequestSpecification specBadRequestNoCookie;
  private static RequestSpecification specBadRequestEmptyCookie;
  private static RequestSpecification specDuplicateKeyCookie;

  private static final String TENANT_DIKU = "diku";
  private static final String REFRESH_PATH = "/authn/refresh";

  @BeforeClass
  public static void setup(final TestContext context) {
    vertx = Vertx.vertx();

    int port = NetworkUtils.nextFreePort();
    int mockPort = NetworkUtils.nextFreePort();

    DeploymentOptions options = new DeploymentOptions().setConfig(
        new JsonObject()
            .put("http.port", port));

    DeploymentOptions mockOptions = new DeploymentOptions().setConfig(
        new JsonObject()
            .put("port", mockPort));

    PostgresClient.setPostgresTester(new PostgresTesterContainer());
    PostgresClient.getInstance(vertx);

    var cookieHeader = LoginAPI.FOLIO_ACCESS_TOKEN + "=321;" + LoginAPI.FOLIO_REFRESH_TOKEN + "=123";
    var cookieHeaderMissingAccessToken = LoginAPI.FOLIO_REFRESH_TOKEN + "=123;";
    var cookieHeaderDuplicateKey = LoginAPI.FOLIO_ACCESS_TOKEN + "=xyz;" + LoginAPI.FOLIO_REFRESH_TOKEN + "=xyz;" + LoginAPI.FOLIO_ACCESS_TOKEN + "=xyz";

    // This kind of request will arrive from browser clients.
    specWithBothAccessAndRefreshTokenCookie = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader("Cookie", cookieHeader)
        .build();

    // This kind of request can arrive from non-browser clients (without the 'automatic' cookie sending).
    specWithoutAccessTokenCookie = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader("Cookie", cookieHeaderMissingAccessToken)
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

    specDuplicateKeyCookie = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader("Cookie", cookieHeaderDuplicateKey)
        .build();

    TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
    vertx.deployVerticle(Mocks.class.getName(), mockOptions)
        .compose(res -> vertx.deployVerticle(RestVerticle.class.getName(), options))
        .compose(res -> TestUtil.postSync(ta, TENANT_DIKU, port, vertx))
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void testRefreshCreated(final TestContext context) {
    validateSpecWithCookieResponse(specWithBothAccessAndRefreshTokenCookie);
  }

  @Test
  public void testOkMissingAccessTokenCookie(final TestContext context) {
    validateSpecWithCookieResponse(specWithoutAccessTokenCookie);
  }

  private void validateSpecWithCookieResponse(RequestSpecification spec) {
    RestAssured.given()
        .spec(spec)
        .when()
        .post(REFRESH_PATH)
        .then()
        .log().all()
        .statusCode(201)
        .contentType("application/json")
        .cookie(LoginAPI.FOLIO_REFRESH_TOKEN, RestAssuredMatchers.detailedCookie()
            .value(Mocks.REFRESH_TOKEN)
            .maxAge(Mocks.REFRESH_TOKEN_EXPIRATION)
            .path("/authn")
            .httpOnly(true)
            .sameSite("Lax")
            .domain(is(nullValue())) // Not setting domain disables subdomains.
            .secured(true))
        .cookie(LoginAPI.FOLIO_ACCESS_TOKEN, RestAssuredMatchers.detailedCookie()
            .value(Mocks.ACCESS_TOKEN)
            .maxAge(Mocks.ACCESS_TOKEN_EXPIRATION)
            .httpOnly(true)
            .sameSite("Lax")
            .path("/") // Access token path is '/'. It is sent on every request.
            .domain(is(nullValue())) // Not setting domain disables subdomains.
            .secured(true))
        .body("$", hasKey(LoginAPI.ACCESS_TOKEN_EXPIRATION))
        .body("$", hasKey(LoginAPI.REFRESH_TOKEN_EXPIRATION));
  }

  @Test
  public void testRefreshBadRequestEmptyCookie(final TestContext context) {
    RestAssured.given()
        .spec(specBadRequestEmptyCookie)
        .when()
        .post(REFRESH_PATH)
        .then()
        .log().all()
        .statusCode(400)
        .body("errors[0].code", is(LoginAPI.TOKEN_PARSE_BAD_CODE))
        .body("errors[0].message", is(LoginAPI.BAD_REQUEST));
  }

  @Test
  public void testRefreshBadRequestNoCookie(final TestContext context) {
    RestAssured.given()
        .spec(specBadRequestNoCookie)
        .when()
        .post(REFRESH_PATH)
        .then()
        .log().all()
        .statusCode(400)
        .body("errors[0].code", is(LoginAPI.TOKEN_PARSE_BAD_CODE))
        .body("errors[0].message", is(LoginAPI.BAD_REQUEST));
  }

  @Test
  public void testDuplicateKeyCookie(final TestContext context) {
    RestAssured.given()
        .spec(specDuplicateKeyCookie)
        .when()
        .post(REFRESH_PATH)
        .then()
        .log().all()
        .statusCode(400)
        .body("errors[0].code", is(LoginAPI.TOKEN_PARSE_BAD_CODE))
        .body("errors[0].message", is(LoginAPI.BAD_REQUEST));
  }
}
