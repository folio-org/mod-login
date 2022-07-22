package org.folio.logintest;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;

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
  private static RequestSpecification spec;
  private static RequestSpecification specExpiredToken;
  private static RequestSpecification specBadRequestMissingAccessTokenCookie;
  private static RequestSpecification specBadRequestNoCookie;
  private static RequestSpecification specBadRequestEmptyCookie;
  private static RequestSpecification specDuplicateKeyCookie;

  private static final String TENANT_DIKU = "diku";
  private static final String REFRESH_PATH = "/authn/refresh";

  @BeforeClass
  public static void setup(final TestContext context) throws Exception {
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

    var cookieHeader = LoginAPI.ACCESS_TOKEN + "=321;" + LoginAPI.REFRESH_TOKEN + "=123";
    var cookieHeaderExpired = LoginAPI.REFRESH_TOKEN + "=abc;" + LoginAPI.ACCESS_TOKEN + "=expiredtoken";
    var cookieHeaderMissingAccessToken = LoginAPI.REFRESH_TOKEN + "=123;";
    var cookieHeaderDuplicateKey = LoginAPI.ACCESS_TOKEN + "=xyz;" + LoginAPI.REFRESH_TOKEN + "=xyz;" + LoginAPI.ACCESS_TOKEN + "=xyz";

    spec = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader("Cookie", cookieHeader)
        .build();

    specExpiredToken = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader("Cookie", cookieHeaderExpired)
        .build();

    specBadRequestMissingAccessTokenCookie = new RequestSpecBuilder()
        .setBaseUri("http://localhost:" + port)
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
    RestAssured.given()
        .spec(spec)
        .when()
        .post(REFRESH_PATH)
        .then()
        .log().all()
        .statusCode(201)
        .contentType("application/json")
        .cookie(LoginAPI.REFRESH_TOKEN, RestAssuredMatchers.detailedCookie()
            .value(Mocks.REFRESH_TOKEN)
            // Account for time drift because we're using Now.Instant to compute max-age.
            .maxAge(allOf(greaterThan(Mocks.REFRESH_TOKEN_EXPIRATION - 2), lessThan(Mocks.REFRESH_TOKEN_EXPIRATION + 1)))
            .path("/authn")
            .httpOnly(true)
            .domain(is(not(emptyOrNullString())))
            .sameSite("None")
            .secured(true))
        .cookie(LoginAPI.ACCESS_TOKEN, RestAssuredMatchers.detailedCookie()
            .value(Mocks.ACCESS_TOKEN)
            // Account for time drift because we're using Now.Instant to compute max-age.
            .maxAge(allOf(greaterThan(Mocks.ACCESS_TOKEN_EXPIRATION - 2), lessThan(Mocks.ACCESS_TOKEN_EXPIRATION + 1)))
            .httpOnly(true)
            .domain(is(not(emptyOrNullString())))
            .sameSite("None")
            .path((String) null) // Access token should not have a path. It is sent on every request.
            .secured(true))
        .body("$", hasKey(LoginAPI.ACCESS_TOKEN_EXPIRATION))
        .body("$", hasKey(LoginAPI.REFRESH_TOKEN_EXPIRATION));
  }

  @Test
  public void testRefreshUnprocessable(final TestContext context) {
    RestAssured.given()
        .spec(specExpiredToken)
        .when()
        .post(REFRESH_PATH)
        .then()
        .log().all()
        .statusCode(422)
        .body("errors[0].code", is(LoginAPI.TOKEN_REFRESH_UNPROCESSABLE_CODE))
        .body("errors[0].message", is(LoginAPI.TOKEN_REFRESH_UNPROCESSABLE));
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
  public void testMissingAccessTokenCookie(final TestContext context) {
    RestAssured.given()
        .spec(specBadRequestMissingAccessTokenCookie)
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
