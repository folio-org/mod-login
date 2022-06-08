package org.folio.logintest;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.util.LoginAttemptsHelper.*;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.folio.logintest.UserMock.gollumId;
import static org.folio.logintest.UserMock.sarumanId;
import static junit.framework.TestCase.assertTrue;

import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.folio.okapi.common.XOkapiHeaders;
import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.Password;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.Criteria.Criterion;
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
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;


@RunWith(VertxUnitRunner.class)
public class LoginWithExpiryTest {

  private static Vertx vertx;
  private static RequestSpecification spec;

  private static final String TENANT_DIKU = "diku";
  private static final String LOGIN_PATH = "/authn/login-with-expiry";
  private static final String CRED_PATH = "/authn/credentials";

  private static final String adminId = "8bd684c1-bbc3-4cf1-bcf4-8013d02a94ce";

  private JsonObject credsObject1 = new JsonObject()
    .put("id", UUID.randomUUID().toString())
    .put("username", "gollum")
    .put("userId", gollumId)
    .put("password", "12345");

  private JsonObject credsObject2 = new JsonObject()
    .put("username", "gollum")
    .put("password", "12345");

  private JsonObject credsObject3 = new JsonObject()
    .put("username", "saruman")
    .put("userId", sarumanId)
    .put("password", "12345");

  private JsonObject credsObject4 = new JsonObject()
    .put("username", "gollum")
    .put("password", "54321");

  private JsonObject credsObject5 = new JsonObject()
    .put("userId", gollumId)
    .put("password", "54321");

  private JsonObject credsObject6 = new JsonObject()
    .put("username", "gollum")
    .put("password", "12345")
    .put("newPassword", "54321");

  private JsonObject credsNoUsernameOrUserId = new JsonObject()
      .put("password", "12345");

  private JsonObject credsNoPassword = new JsonObject()
      .put("username", "gollum");

  private JsonObject credsElicitEmptyUserResp = new JsonObject()
      .put("username", "mrunderhill")
      .put("password", "54321");

  private JsonObject credsElicitBadUserResp = new JsonObject()
      .put("username", "gimli")
      .put("password", "54321");

  private JsonObject credsNonExistentUser = new JsonObject()
      .put("username", "mickeymouse")
      .put("password", "54321");

  private JsonObject credsElicitMultiUserResp = new JsonObject()
      .put("username", "gandalf")
      .put("password", "54321");

  private JsonObject credsUserWithNoId = new JsonObject()
      .put("username", "strider")
      .put("password", "54321");

  private JsonObject credsEmptyStringPassword = new JsonObject()
      .put("username", "saruman")
      .put("userId", sarumanId)
      .put("password", "");

  private JsonObject newCredsEmptyPassword = new JsonObject()
      .put("username", "gollum")
      .put("password", "12345")
      .put("newPassword", "");

  private static Map<String, String> moduleArgs;

  @BeforeClass
  public static void setup(final TestContext context) throws Exception {
    moduleArgs = new HashMap<String,String>(MODULE_SPECIFIC_ARGS);
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

    TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
    vertx.deployVerticle(UserMock.class.getName(), mockOptions)
        .compose(res -> vertx.deployVerticle(RestVerticle.class.getName(), options))
        .compose(res -> TestUtil.postSync(ta, TENANT_DIKU, port, vertx))
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void testLoginWithExpiry(final TestContext context) {
    // TODO Add more tests that mirror the tests in RestVerticleTest for the /login route.
    RestAssured.given()
      .spec(spec)
      .body(credsObject1.encode())
      .when()
      .post(CRED_PATH)
      .then()
      .log().all()
      .statusCode(201);

    // TODO Do no token test by removing the token.

    // Login without password.
    RestAssured.given()
      .spec(spec)
      .body(credsNoPassword.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(400);
    // TODO Where to get this?
    //.body("errors[0].code", equalTo("password.incorrect")); 

    // Login success.
    RestAssured.given()
      .spec(spec)
      .body(credsObject1.encode())
      .when()
      .post(LOGIN_PATH)
      .then()
      .log().all()
      .statusCode(201)
      .body("accessToken", is("dummyaccesstoken"))
      .body("accessTokenExpiration", is("atisodatestring"))
      .body("refreshTokenExpiration", is("rtisodatestring"))
      .header("Set-Cookie", is("refreshToken=dummyrefreshtoken; HttpOnly; path=/authn/refresh"));
  }

}
