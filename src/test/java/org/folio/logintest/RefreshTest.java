package org.folio.logintest;

import static org.hamcrest.Matchers.is;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;

import com.github.tomakehurst.wiremock.junit.WireMockClassRule;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.RestVerticle;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.junit.BeforeClass;
import org.junit.ClassRule;
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
  private static RequestSpecification specWithOkapiUrlPath;
  private static RequestSpecification specBadRequestNoCookie;
  private static RequestSpecification specBadRequestEmptyCookie;
  private static RequestSpecification specDuplicateKeyCookie;

  private static final String TENANT_DIKU = "diku";
  private static final String REFRESH_PATH = "/authn/refresh";
  private static final int OKAPI_PROXY_PORT = NetworkUtils.nextFreePort();

  @ClassRule
  public static WireMockClassRule okapiProxy = new WireMockClassRule(OKAPI_PROXY_PORT);

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

    okapiProxy.stubFor(any(urlMatching("/okapi/.*"))
        .willReturn(aResponse()
            .proxiedFrom("http://localhost:" + mockPort)
            .withProxyUrlPrefixToRemove("/okapi")));

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

    specWithOkapiUrlPath = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + OKAPI_PROXY_PORT + "/okapi")
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader("Cookie", cookieHeader)
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
  public void testRefreshCreated() {
    validateSpecWithCookieResponse(specWithBothAccessAndRefreshTokenCookie);
  }

  @Test
  public void testOkMissingAccessTokenCookie() {
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
            .maxAge(is(oneOf(Mocks.REFRESH_TOKEN_EXPIRATION - 1L, (long) Mocks.REFRESH_TOKEN_EXPIRATION)))
            .path("/authn")
            .httpOnly(true)
            .sameSite("Lax")
            .domain(is(nullValue())) // Not setting domain disables subdomains.
            .secured(true))
        .cookie(LoginAPI.FOLIO_ACCESS_TOKEN, RestAssuredMatchers.detailedCookie()
            .value(Mocks.ACCESS_TOKEN)
            .maxAge(is(oneOf(Mocks.ACCESS_TOKEN_EXPIRATION - 1L, (long) Mocks.ACCESS_TOKEN_EXPIRATION)))
            .httpOnly(true)
            .sameSite("Lax")
            .path("/") // Access token path is '/'. It is sent on every request.
            .domain(is(nullValue())) // Not setting domain disables subdomains.
            .secured(true))
        .body("$", hasKey(LoginAPI.ACCESS_TOKEN_EXPIRATION))
        .body("$", hasKey(LoginAPI.REFRESH_TOKEN_EXPIRATION));
  }

  @Test
  public void testOkapiUrlPath() {
    RestAssured.given()
    .spec(specWithOkapiUrlPath)
    .when()
    .post(REFRESH_PATH)
    .then()
    .log().all()
    .statusCode(201)
    .contentType("application/json")
    .cookie(LoginAPI.FOLIO_REFRESH_TOKEN, RestAssuredMatchers.detailedCookie()
        .path("/okapi/authn"))
    .cookie(LoginAPI.FOLIO_ACCESS_TOKEN, RestAssuredMatchers.detailedCookie()
        .path("/okapi/"));
  }

  @Test
  public void testRefreshBadRequestEmptyCookie() {
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
  public void testRefreshBadRequestNoCookie() {
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
  public void testDuplicateKeyCookie() {
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
