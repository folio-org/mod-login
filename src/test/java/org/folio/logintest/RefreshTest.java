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
import io.restassured.matcher.DetailedCookieMatcher;
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
  private static RequestSpecification specWithoutUrl;
  private static RequestSpecification specWithoutToken;

  private static final String TENANT_DIKU = "diku";
  //private static final String LOGIN_WITH_EXPIRY_PATH = "/authn/login-with-expiry";
  private static final String REFRESH_PATH = "/authn/refresh";
  //private static final String CRED_PATH = "/authn/credentials";
  //private static final String UPDATE_PATH = "/authn/update";

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
        .addHeader("Cookie", "at=123; rt=321")
        //.addHeader(XOkapiHeaders.TOKEN, "dummy.token")
        .addHeader(XOkapiHeaders.REQUEST_TIMESTAMP, String.valueOf(new Date().getTime()))
        .build();

    specWithoutUrl = new RequestSpecBuilder()
        .setContentType(ContentType.JSON)
        .setBaseUri("http://localhost:" + port)
        .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
        //.addHeader(XOkapiHeaders.TOKEN, "dummy.token")
        .addHeader(XOkapiHeaders.REQUEST_TIMESTAMP, String.valueOf(new Date().getTime()))
        .build();

    // specWithoutToken = new RequestSpecBuilder()
    //     .setContentType(ContentType.JSON)
    //     .setBaseUri("http://localhost:" + port)
    //     .addHeader(XOkapiHeaders.URL, "http://localhost:" + mockPort)
    //     .addHeader(XOkapiHeaders.TENANT, TENANT_DIKU)
    //     .addHeader(XOkapiHeaders.REQUEST_TIMESTAMP, String.valueOf(new Date().getTime()))
    //     .build();

    TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
    vertx.deployVerticle(Mocks.class.getName(), mockOptions)
        .compose(res -> vertx.deployVerticle(RestVerticle.class.getName(), options))
        .compose(res -> TestUtil.postSync(ta, TENANT_DIKU, port, vertx))
        .onComplete(context.asyncAssertSuccess());
  }

  // TODO Get this going
  // @Test
  // public void testRefresh(final TestContext context) {
  //   RestAssured.given()
  //     .spec(spec)
  //     .when()
  //     .post(REFRESH_PATH)
  //     .then()
  //     .log().all()
  //     .statusCode(201)
  //     .contentType("application/json")
  //     .cookie("refreshToken", RestAssuredMatchers.detailedCookie()
  //         .value("dummyrefreshtoken")
  //         .maxAge(604800)
  //         .path("/authn/refresh")
  //         .httpOnly(true)
  //         .secured(true))
  //     .cookie("accessToken", RestAssuredMatchers.detailedCookie()
  //         .value("dummyaccesstoken")
  //         .maxAge(600)
  //         .httpOnly(true)
  //         .secured(true))
  //     .body("$", hasKey("accessTokenExpiration"))
  //     .body("$", hasKey("refreshTokenExpiration"));
  // }
}
