package org.folio.logintest;

import static org.folio.logintest.Mocks.credsObject1;
import static org.folio.logintest.Mocks.credsObject2;
import static org.folio.logintest.Mocks.credsObject3;
import static org.folio.logintest.Mocks.credsObject4;
import static org.folio.logintest.Mocks.credsObject5;
import static org.folio.logintest.Mocks.credsObject6;
import static org.folio.logintest.Mocks.credsElicitBadUserResp;
import static org.folio.logintest.Mocks.credsElicitEmptyUserResp;
import static org.folio.logintest.Mocks.credsElicitMultiUserResp;
import static org.folio.logintest.Mocks.credsNoPassword;
import static org.folio.logintest.Mocks.credsNoUsernameOrUserId;
import static org.folio.logintest.Mocks.credsNonExistentUser;
import static org.folio.logintest.Mocks.credsUserWithNoId;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;

import java.util.Date;

import org.folio.okapi.common.XOkapiHeaders;
import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
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
public class LoginWithExpiryTest {

  private static Vertx vertx;
  private static RequestSpecification spec;
  private static RequestSpecification specWithoutUrl;
  private static RequestSpecification specWithoutToken;

  private static final String TENANT_DIKU = "diku";
  private static final String LOGIN_WITH_EXPIRY_PATH = "/authn/login-with-expiry";
  private static final String CRED_PATH = "/authn/credentials";
  private static final String UPDATE_PATH = "/authn/update";

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

    specWithoutToken = new RequestSpecBuilder()
        .setContentType(ContentType.JSON)
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
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
      .spec(specWithoutToken)
      .body(credsNoPassword.encode())
      .when()
      .post(LOGIN_WITH_EXPIRY_PATH)
      .then()
      .log().all()
      .statusCode(400)
      .contentType("text/plain")
      .body(is("Missing Okapi token header"));

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
      .body("errors[0].message", is("Error verifying user existence: Error, missing field(s) 'totalRecords' and/or 'users' in user response object"));

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

    // This test should pass but it fails because of the cookie merging.
    // In the very old days of cookies merging cookies into a single Set-Cookie header
    // was possible when separating the cookie data with a comma. This was called "cookie folding"
    // but AFAIK UAs don't support this anymore and the accepted way is to have multiple
    // Set-Cookie headers. See MDN on this.
    RestAssured.given()
      .spec(spec)
      .body(credsObject1.encode())
      .when()
      .post(LOGIN_WITH_EXPIRY_PATH)
      .then()
      .log().all()
      .statusCode(200)
      .header("Set-Cookie", containsString("at=abc123; HttpOnly; Max-Age=123;"))
      .header("Set-Cookie", containsString("rt=xyz321;"));

    RestAssured.given()
      .spec(spec)
      .body(credsObject1.encode())
      .when()
      .post(LOGIN_WITH_EXPIRY_PATH)
      .then()
      .log().all()
      .statusCode(201)
      .header("Set-Cookie", containsString("refreshToken=dummyrefreshtoken;"))
      .header("Set-Cookie", containsString("HttpOnly;"))
      .header("Set-Cookie", containsString("Path=/authn/refresh;"))
      .header("Set-Cookie", containsString("Max-Age=604800"))
      .body("accessToken", is("dummyaccesstoken"))
      .body("$", hasKey("accessTokenExpiration"))
      .body("$", hasKey("refreshTokenExpiration"));

    RestAssured.given()
      .spec(spec)
      .body(credsObject2.encode())
      .when()
      .post(LOGIN_WITH_EXPIRY_PATH)
      .then()
      .log().all()
      .statusCode(201)
      .header("Set-Cookie", containsString("refreshToken=dummyrefreshtoken;"))
      .header("Set-Cookie", containsString("HttpOnly;"))
      .header("Set-Cookie", containsString("Path=/authn/refresh;"))
      .header("Set-Cookie", containsString("Max-Age=604800"))
      .body("accessToken", is("dummyaccesstoken"))
      .body("$", hasKey("accessTokenExpiration"))
      .body("$", hasKey("refreshTokenExpiration"));

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
    // One provides the userId and the other provides the username, but neither should work.
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
    RestAssured.given()
      .spec(spec)
      .body(credsObject4.encode())
      .when()
      .post(LOGIN_WITH_EXPIRY_PATH)
      .then()
      .log().all()
      .statusCode(201)
      .header("Set-Cookie", containsString("refreshToken=dummyrefreshtoken;"))
      .header("Set-Cookie", containsString("HttpOnly;"))
      .header("Set-Cookie", containsString("Path=/authn/refresh;"))
      .header("Set-Cookie", containsString("Max-Age=604800"))
      .body("accessToken", is("dummyaccesstoken"))
      .body("$", hasKey("accessTokenExpiration"))
      .body("$", hasKey("refreshTokenExpiration"));


    RestAssured.given()
      .spec(spec)
      .body(credsObject5.encode())
      .when()
      .post(LOGIN_WITH_EXPIRY_PATH)
      .then()
      .log().all()
      .statusCode(201)
      .header("Set-Cookie", containsString("refreshToken=dummyrefreshtoken;"))
      .header("Set-Cookie", containsString("HttpOnly;"))
      .header("Set-Cookie", containsString("Path=/authn/refresh;"))
      .header("Set-Cookie", containsString("Max-Age=604800"))
      .body("accessToken", is("dummyaccesstoken"))
      .body("$", hasKey("accessTokenExpiration"))
      .body("$", hasKey("refreshTokenExpiration"));
  }
}
