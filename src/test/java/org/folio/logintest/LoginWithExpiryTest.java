package org.folio.logintest;

import static org.folio.logintest.Mocks.*;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.nullValue;

import org.folio.rest.impl.LoginAPI;

import java.util.Date;
import java.util.Map;

import org.folio.okapi.common.XOkapiHeaders;
import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.folio.util.CookieSameSiteConfig;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.matcher.RestAssuredMatchers;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;

@RunWith(VertxUnitRunner.class)
public class LoginWithExpiryTest {

  private static Vertx vertx;
  private static RequestSpecification spec;
  private static RequestSpecification specWithoutUrl;
  private static RequestSpecification specWithOkapiUrlPath;

  private static final String TENANT_DIKU = "diku";
  private static final String LOGIN_WITH_EXPIRY_PATH = "/authn/login-with-expiry";
  private static final String CRED_PATH = "/authn/credentials";
  private static final String UPDATE_PATH = "/authn/update";

  private static void cleanup() {
    CookieSameSiteConfig.set(Map.of());
    System.clearProperty(TOKEN_FETCH_ERROR_STATUS);
  }

  @After
  public void after() {
    cleanup();
  }

  @BeforeClass
  public static void setup(final TestContext context) {
    cleanup();

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

    spec = new RequestSpecBuilder()
        .setContentType(ContentType.JSON)
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader(XOkapiHeaders.TOKEN, "dummy.token")
        .addHeader(XOkapiHeaders.REQUEST_TIMESTAMP, String.valueOf(new Date().getTime()))
        .build();

    specWithoutUrl = new RequestSpecBuilder()
        .setContentType(ContentType.JSON)
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader(XOkapiHeaders.TOKEN, "dummy.token")
        .addHeader(XOkapiHeaders.REQUEST_TIMESTAMP, String.valueOf(new Date().getTime()))
        .build();

    specWithOkapiUrlPath = new RequestSpecBuilder()
        .setContentType(ContentType.JSON)
        .setBaseUri("http://localhost:" + port + "/okapi")
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        .addHeader(XOkapiHeaders.TOKEN, "dummy.token")
        .addHeader(XOkapiHeaders.REQUEST_TIMESTAMP, String.valueOf(new Date().getTime()))
        .build();

    TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
    vertx.deployVerticle(Mocks.class.getName(), mockOptions)
        .compose(res -> vertx.deployVerticle(RestVerticle.class.getName(), options))
        .compose(res -> TestUtil.postSync(ta, TENANT_DIKU, port, vertx))
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void testLoginWithExpiry() {
    RestAssured.given()
        .spec(spec)
        .body(credsObject1.encode())
        .when()
        .post(CRED_PATH)
        .then()
        .log().all()
        .statusCode(201);

    RestAssured.given()
        .spec(specWithoutUrl)
        .body(credsNoPassword.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(400)
        .contentType("text/plain")
        .body(is("Missing X-Okapi-Url header"));

    RestAssured.given()
        .spec(spec)
        .body(credsNoPassword.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(400)
        .contentType("text/plain")
        .body(is("You must provide a password"));

    RestAssured.given()
        .spec(spec)
        .body(credsNoUsernameOrUserId.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(400)
        .contentType("text/plain")
        .body(is("You must provide a username or userId"));

    RestAssured.given()
        .spec(spec)
        .body(credsElicitEmptyUserResp.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(422)
        .contentType("application/json")
        .body("errors[0].code", is("wrong.username.or.wrong.password.or.account.blocked"))
        .body("errors[0].message", is("Wrong username or wrong password or account blocked"));

    RestAssured.given()
        .spec(spec)
        .body(credsElicitBadUserResp.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(422)
        .contentType("application/json")
        .body("errors[0].code", is("wrong.username.or.wrong.password.or.account.blocked"))
        .body("errors[0].message", is("Wrong username or wrong password or account blocked"));

    RestAssured.given()
        .spec(spec)
        .body(credsNonExistentUser.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(422)
        .contentType("application/json")
        .body("errors[0].code", is("wrong.username.or.wrong.password.or.account.blocked"))
        .body("errors[0].message", is("Wrong username or wrong password or account blocked"));

    RestAssured.given()
        .spec(spec)
        .body(credsElicitMultiUserResp.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(422)
        .contentType("application/json")
        .body("errors[0].code", is("wrong.username.or.wrong.password.or.account.blocked"))
        .body("errors[0].message", is("Wrong username or wrong password or account blocked"));

    RestAssured.given()
        .spec(spec)
        .body(credsUserWithNoId.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(500)
        .contentType("text/plain")
        .body(is("Internal Server error"));

    // SameSite=Lax is the default
    testCookieResponse(credsObject1, "Lax");
    testCookieResponse(credsObject2, "Lax");

    // SameSite=Lax
    CookieSameSiteConfig.set(Map.of("LOGIN_COOKIE_SAMESITE", "None"));
    testCookieResponse(credsObject1, "None");
    testCookieResponse(credsObject2, "None");

    // default SameSite=Lax
    CookieSameSiteConfig.set(Map.of());

    // Post a credentials object which doesn't have an id property.
    RestAssured.given()
        .spec(spec)
        .body(credsObject3.encode())
        .when()
        .post(CRED_PATH)
        .then()
        .log().all()
        .statusCode(201);

    // The credentials object doesn't have a id so it is not considered active.
    RestAssured.given()
        .spec(spec)
        .body(credsObject3.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(422)
        .contentType("application/json")
        .body("errors[0].code", is("wrong.username.or.wrong.password.or.account.blocked"))
        .body("errors[0].message", is("Wrong username or wrong password or account blocked"));

    // The following credentials objects have incorrect passwords.
    // One provides the userId and the other provides the username, but neither
    // should work.
    RestAssured.given()
        .spec(spec)
        .body(credsObject4.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(422)
        .contentType("application/json")
        .body("errors[0].code", is("wrong.username.or.wrong.password.or.account.blocked"))
        .body("errors[0].message", is("Wrong username or wrong password or account blocked"));

    RestAssured.given()
        .spec(spec)
        .body(credsObject5.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(422)
        .contentType("application/json")
        .body("errors[0].code", is("wrong.username.or.wrong.password.or.account.blocked"))
        .body("errors[0].message", is("Wrong username or wrong password or account blocked"));

    // Now we update our credentials object with a new password and try again.
    RestAssured.given()
        .spec(spec)
        .body(credsObject6.encode())
        .when()
        .post(UPDATE_PATH)
        .then()
        .log().all()
        .statusCode(204);

    // These should now succeed.
    testCookieResponse(credsObject4, "Lax");
    testCookieResponse(credsObject5, "Lax");

    // TEst 404 if legacy endpoint isn't available.
    System.setProperty(Mocks.TOKEN_FETCH_ERROR_STATUS, "404");
    RestAssured.given()
        .spec(spec)
        .body(credsObject4.encode())
        .when()
        .post("/authn/login")
        .then()
        .log().all()
        .statusCode(404);

    System.setProperty(Mocks.TOKEN_FETCH_ERROR_STATUS, "500");
    RestAssured.given()
        .spec(spec)
        .body(credsObject4.encode())
        .when()
        .post("/authn/login")
        .then()
        .log().all()
        .statusCode(500);
  }

  private void testCookieResponse(JsonObject creds, String sameSite) {
    RestAssured.given()
        .spec(spec)
        .body(creds.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(201)
        .contentType("application/json")
        .cookie(LoginAPI.FOLIO_REFRESH_TOKEN, RestAssuredMatchers.detailedCookie()
            .value(Mocks.REFRESH_TOKEN)
            .maxAge(is(oneOf(Mocks.REFRESH_TOKEN_EXPIRATION - 1L, (long) Mocks.REFRESH_TOKEN_EXPIRATION)))
            .path("/authn") // Refresh is restricted to this domain.
            .httpOnly(true)
            .secured(true)
            .domain(is(nullValue())) // Not setting domain disables subdomains.
            .sameSite(sameSite))
        .cookie(LoginAPI.FOLIO_ACCESS_TOKEN, RestAssuredMatchers.detailedCookie()
            .value(Mocks.ACCESS_TOKEN)
            .maxAge(is(oneOf(Mocks.ACCESS_TOKEN_EXPIRATION - 1L, (long) Mocks.ACCESS_TOKEN_EXPIRATION)))
            .path("/") // Path must be set in this way for it to mean "all paths".
            .httpOnly(true)
            .secured(true)
            .domain(is(nullValue())) // Not setting domain disables subdomains.
            .sameSite(sameSite))
        .body("$", hasKey(LoginAPI.ACCESS_TOKEN_EXPIRATION))
        .body("$", hasKey(LoginAPI.REFRESH_TOKEN_EXPIRATION));
  }

  public void testWithOkapiUrlPath() {
    RestAssured.given()
    .spec(specWithOkapiUrlPath)
    .body(credsObject1.encode())
    .when()
    .post(LOGIN_WITH_EXPIRY_PATH)
    .then()
    .log().all()
    .statusCode(201)
    .contentType("application/json")
    .cookie(LoginAPI.FOLIO_REFRESH_TOKEN, RestAssuredMatchers.detailedCookie()
        .path("/okapi/authn"))
    .cookie(LoginAPI.FOLIO_ACCESS_TOKEN, RestAssuredMatchers.detailedCookie()
        .path("/okapi/"));
  }
}
