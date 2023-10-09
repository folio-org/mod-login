package org.folio.logintest;

import static org.folio.logintest.Mocks.*;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;

import org.folio.rest.impl.LoginAPI;

import java.util.Date;

import org.folio.okapi.common.XOkapiHeaders;
import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.junit.AfterClass;
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

  private static final String TENANT_DIKU = "diku";
  private static final String LOGIN_WITH_EXPIRY_PATH = "/authn/login-with-expiry";
  private static final String CRED_PATH = "/authn/credentials";
  private static final String UPDATE_PATH = "/authn/update";

  @AfterClass
  public static void clearSystemProperties() {
    System.clearProperty(TOKEN_FETCH_ERROR_STATUS);
  }
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

    TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
    vertx.deployVerticle(Mocks.class.getName(), mockOptions)
        .compose(res -> vertx.deployVerticle(RestVerticle.class.getName(), options))
        .compose(res -> TestUtil.postSync(ta, TENANT_DIKU, port, vertx))
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void testLoginWithExpiry(final TestContext context) {
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
        .body("errors[0].code", is("username.incorrect"))
        .body("errors[0].message", is("Error verifying user existence: No user found by username mrunderhill"));

    RestAssured.given()
        .spec(spec)
        .body(credsElicitBadUserResp.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(422)
        .contentType("application/json")
        .body("errors[0].code", is("username.incorrect"))
        .body("errors[0].message", is(
            "Error verifying user existence: Error, missing field(s) 'totalRecords' and/or 'users' in user response object"));

    RestAssured.given()
        .spec(spec)
        .body(credsNonExistentUser.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(422)
        .contentType("application/json")
        .body("errors[0].code", is("username.incorrect"))
        .body("errors[0].message", is("Error verifying user existence: No user found by username mickeymouse"));

    RestAssured.given()
        .spec(spec)
        .body(credsElicitMultiUserResp.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(422)
        .contentType("application/json")
        .body("errors[0].code", is("username.incorrect"))
        .body("errors[0].message", is("Error verifying user existence: Bad results from username"));

    RestAssured.given()
        .spec(spec)
        .body(credsUserWithNoId.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(500)
        .contentType("text/plain")
        .body(is("No user id could be found"));

    // SameSite=None is the default and required if the origin for the HTML and the host
    // for the backend are different.
    testCookieResponse(credsObject1, LoginAPI.COOKIE_SAME_SITE_NONE);
    testCookieResponse(credsObject2, LoginAPI.COOKIE_SAME_SITE_NONE);

    // Setting SameSite=Lax is an option if the origin for the HTML and the backed are the same. This is more secure.
    System.setProperty(LoginAPI.COOKIE_SAME_SITE, LoginAPI.COOKIE_SAME_SITE_LAX);
    testCookieResponse(credsObject1, LoginAPI.COOKIE_SAME_SITE_LAX);
    testCookieResponse(credsObject2, LoginAPI.COOKIE_SAME_SITE_LAX);
    System.clearProperty(LoginAPI.COOKIE_SAME_SITE);

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
        .body("errors[0].code", is("user.blocked"))
        .body("errors[0].message", is("User must be flagged as active"));

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
        .body("errors[0].code", is("password.incorrect"))
        .body("errors[0].message", is("Password does not match"));

    RestAssured.given()
        .spec(spec)
        .body(credsObject5.encode())
        .when()
        .post(LOGIN_WITH_EXPIRY_PATH)
        .then()
        .log().all()
        .statusCode(422)
        .contentType("application/json")
        .body("errors[0].code", is("password.incorrect.block.user"))
        .body("errors[0].message", is("Fifth failed attempt"));

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
    testCookieResponse(credsObject4, LoginAPI.COOKIE_SAME_SITE_NONE);
    testCookieResponse(credsObject5, LoginAPI.COOKIE_SAME_SITE_NONE);

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
    System.clearProperty(TOKEN_FETCH_ERROR_STATUS);
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
            .maxAge(Mocks.REFRESH_TOKEN_EXPIRATION)
            .path("/authn") // Refresh is restricted to this domain.
            .httpOnly(true)
            .secured(true)
            .domain(is(nullValue())) // Not setting domain disables subdomains.
            .sameSite(sameSite))
        .cookie(LoginAPI.FOLIO_ACCESS_TOKEN, RestAssuredMatchers.detailedCookie()
            .value(Mocks.ACCESS_TOKEN)
            .maxAge(Mocks.ACCESS_TOKEN_EXPIRATION)
            .path("/") // Path must be set in this way for it to mean "all paths".
            .httpOnly(true)
            .secured(true)
            .domain(is(nullValue())) // Not setting domain disables subdomains.
            .sameSite(sameSite))
        .body("$", hasKey(LoginAPI.ACCESS_TOKEN_EXPIRATION))
        .body("$", hasKey(LoginAPI.REFRESH_TOKEN_EXPIRATION));
  }
}
