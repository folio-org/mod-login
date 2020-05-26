package org.folio.logintest;

import static org.folio.logintest.TestUtil.doRequest;
import static org.folio.logintest.UserMock.gollumId;
import static org.folio.logintest.UserMock.sarumanId;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.folio.logintest.TestUtil.WrappedResponse;
import org.folio.rest.RestVerticle;
import org.folio.rest.client.TenantClient;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.jaxrs.model.Parameter;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.tools.utils.NetworkUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;

@RunWith(VertxUnitRunner.class)
public class RestVerticleTest {

  private JsonObject credsObject1 = new JsonObject()
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

  private static Vertx vertx;
  private static int port;
  private static int mockPort;
  private static MultiMap headers;

  private final Logger logger = LoggerFactory.getLogger(RestVerticleTest.class);
  private static String credentialsUrl;
  private static String loginUrl;
  private static String updateUrl;
  private static String okapiUrl;

  @Rule
  public Timeout rule = Timeout.seconds(200);  // 3 minutes for loading embedded postgres

  @BeforeClass
  public static void setup(TestContext context) {
    Async async = context.async();
    port = NetworkUtils.nextFreePort();
    mockPort = NetworkUtils.nextFreePort(); //get another
    TenantClient tenantClient = new TenantClient("http://localhost:" + port, "diku", "diku");
    vertx = Vertx.vertx();
    credentialsUrl = "http://localhost:" + port + "/authn/credentials";
    loginUrl = "http://localhost:" + port + "/authn/login";
    updateUrl = "http://localhost:" + port + "/authn/update";
    okapiUrl = "http://localhost:" + mockPort;

    headers = MultiMap.caseInsensitiveMultiMap();
    headers.add(RestVerticle.OKAPI_HEADER_TOKEN, "dummytoken");
    headers.add(LoginAPI.OKAPI_URL_HEADER, okapiUrl);
    headers.add(LoginAPI.OKAPI_REQUEST_TIMESTAMP_HEADER, String.valueOf(new Date().getTime()));

    DeploymentOptions options = new DeploymentOptions().setConfig(
      new JsonObject()
        .put("http.port", port)
    );
    DeploymentOptions mockOptions = new DeploymentOptions().setConfig(
      new JsonObject()
        .put("port", mockPort)).setWorker(true);


    try {
      PostgresClient.setIsEmbedded(true);
      PostgresClient.getInstance(vertx).startEmbeddedPostgres();
    } catch (Exception e) {
      e.printStackTrace();
      context.fail(e);
      return;
    }

    vertx.deployVerticle(UserMock.class.getName(), mockOptions, mockRes -> {
      if (mockRes.failed()) {
        mockRes.cause().printStackTrace();
        context.fail(mockRes.cause());
      } else {
        vertx.deployVerticle(RestVerticle.class.getName(), options, res -> {
          try {
            TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.0.0");
            List<Parameter> parameters = new LinkedList<>();
            parameters.add(new Parameter().withKey("loadSample").withValue("true"));
            ta.setParameters(parameters);
            tenantClient.postTenant(ta, res2 -> {
              async.complete();
            });
          } catch (Exception e) {
            e.printStackTrace();
          }
        });
      }
    });

  }

  @Before
  public void setUp(TestContext context) {
    Async async = context.async();
    PostgresClient pgClient = PostgresClient.getInstance(vertx, "diku");
    pgClient.startTx(beginTx ->
      pgClient.delete(beginTx, "auth_attempts", new Criterion(), event -> {
        if (event.failed()) {
          pgClient.rollbackTx(beginTx, e -> context.fail(event.cause()));
        } else {
          pgClient.delete(beginTx, "auth_credentials", new Criterion(), eventAuth -> {
            if (eventAuth.failed()) {
              pgClient.rollbackTx(beginTx, e -> context.fail(eventAuth.cause()));
            } else { // auth_credentials_history
              pgClient.delete(beginTx, "auth_credentials_history", new Criterion(), eventHistory -> {
                if (eventHistory.failed()) {
                  pgClient.rollbackTx(beginTx, e -> context.fail(eventHistory.cause()));
                } else {
                  pgClient.endTx(beginTx, ev -> {
                    event.succeeded();
                    async.complete();
                  });
                }
              });
            }
          });
        }
      }));
  }

  @AfterClass
  public static void teardown(TestContext context) {
    Async async = context.async();
    vertx.close(context.asyncAssertSuccess(res -> {
      PostgresClient.stopEmbeddedPostgres();
      async.complete();
    }));
  }

  @Test
  public void testPermsSeq(TestContext context) {
    Async async = context.async();
    String[] credsObject1Id = new String[1];
    Future<WrappedResponse> chainedFuture =
      postNewCredentials(context, credsObject1).compose(w -> {
        credsObject1Id[0] = w.getJson().getString("id");
        return postDuplicateCredentials(context, credsObject1);
      })
        .compose(w -> getCredentials(context, credsObject1Id[0]))
        .compose(w -> testMockUser(context, "gollum", null))
        .compose(w -> testMockUser(context, null, gollumId))
        .compose(w -> failMockUser(context, "yomomma", null))
        .compose(w -> doLoginNoToken(context, credsObject1))
        .compose(w -> doLoginNoPassword(context, credsNoPassword))
        .compose(w -> doLoginNoUsernameOrUserId(context, credsNoUsernameOrUserId))
        .compose(w -> doLoginEmptyUserResponse(context, credsElicitEmptyUserResp))
        .compose(w -> doLoginBadUserResponse(context, credsElicitBadUserResp))
        .compose(w -> doLoginNonExistentUser(context, credsNonExistentUser))
        .compose(w -> doLoginMultiUserRespose(context, credsElicitMultiUserResp))
        .compose(w -> doLogin(context, credsObject1))
        .compose(w -> doLogin(context, credsObject2))
        .compose(w -> postNewCredentials(context, credsObject3))
        .compose(w -> doInactiveLogin(context, credsObject3))
        .compose(w -> doBadPasswordLogin(context, credsObject4))
        .compose(w -> doBadPasswordLogin(context, credsObject5))
        .compose(w -> doUpdatePassword(context, credsObject6))
        .compose(w -> doLogin(context, credsObject4))
        .compose(w -> doLogin(context, credsObject5))
        .compose(w -> doBadCredentialsUpdatePassword(context, credsObject6))
        .compose(w -> doBadInputUpdatePassword(context, credsObject4));
    chainedFuture.onComplete(chainedRes -> {
      if (chainedRes.failed()) {
        logger.error("Test failed: " + chainedRes.cause().getLocalizedMessage());
        context.fail(chainedRes.cause());
      } else {
        async.complete();
      }
    });
  }

  class HTTPResponseHandler implements Handler<HttpClientResponse> {

    CompletableFuture<Response> event;

    public HTTPResponseHandler(CompletableFuture<Response> cf) {
      event = cf;
    }

    @Override
    public void handle(HttpClientResponse hcr) {
      hcr.bodyHandler(bh -> {
        Response r = new Response();
        r.code = hcr.statusCode();
        try {
          r.body = bh.toJsonObject();
        } catch (Exception e) {
          r.body = null;
        }
        event.complete(r);
      });
    }
  }

  class HTTPNoBodyResponseHandler implements Handler<HttpClientResponse> {

    CompletableFuture<Response> event;

    public HTTPNoBodyResponseHandler(CompletableFuture<Response> cf) {
      event = cf;
    }

    @Override
    public void handle(HttpClientResponse hcr) {
      Response r = new Response();
      r.code = hcr.statusCode();
      event.complete(r);
    }
  }

  class Response {
    int code;
    JsonObject body;
  }

  private Future<WrappedResponse> postNewCredentials(TestContext context,
                                                     JsonObject newCredentials) {
    return doRequest(vertx, credentialsUrl, HttpMethod.POST, null, newCredentials.encode(),
      201, "Add a new credential object");
  }

  private Future<WrappedResponse> postDuplicateCredentials(TestContext context,
                                                           JsonObject newCredentials) {
    return doRequest(vertx, credentialsUrl, HttpMethod.POST, null, newCredentials.encode(),
      422, "Try to add a duplicate credential object");
  }

  private Future<WrappedResponse> getCredentials(TestContext context, String credsId) {
    return doRequest(vertx, credentialsUrl + "/" + credsId, HttpMethod.GET, null, null,
      200, "Retrieve an existing credential by id");
  }

  private Future<WrappedResponse> testMockUser(TestContext context, String username, String userId) {
    String url;
    if (username != null) {
      url = okapiUrl + "/users?query=username==" + username;
    } else {
      url = okapiUrl + "/users?query=id==" + userId;
    }
    return doRequest(vertx, url, HttpMethod.GET, null, null, 200,
      "Test mock /user endpoint at url " + url);
  }

  private Future<WrappedResponse> failMockUser(TestContext context, String username, String userId) {
    String url;
    if (username != null) {
      url = okapiUrl + "/users?query=username==" + username;
    } else {
      url = okapiUrl + "/users?query=id==" + userId;
    }
    return doRequest(vertx, url, HttpMethod.GET, null, null, 404,
      "Fail nonexistent mock /user endpoint at url " + url);
  }

  private Future<WrappedResponse> doLogin(TestContext context, JsonObject loginCredentials) {
    return doRequest(vertx, loginUrl, HttpMethod.POST, headers, loginCredentials.encode(),
      201, "Login with created credentials");
  }

  private Future<WrappedResponse> doLoginNoUsernameOrUserId(TestContext context, JsonObject loginCredentials) {
    return doRequest(vertx, loginUrl, HttpMethod.POST, headers, loginCredentials.encode(),
      400, "You must provide a username or userId");
  }

  private Future<WrappedResponse> doLoginNoPassword(TestContext context, JsonObject loginCredentials) {
    return doRequest(vertx, loginUrl, HttpMethod.POST, headers, loginCredentials.encode(),
      400, "You must provide a password");
  }

  private Future<WrappedResponse> doLoginNoToken(TestContext context, JsonObject loginCredentials) {
    MultiMap headersNoToken = MultiMap.caseInsensitiveMultiMap();
    headersNoToken.addAll(headers);
    headersNoToken.remove(RestVerticle.OKAPI_HEADER_TOKEN);

    return doRequest(vertx, loginUrl, HttpMethod.POST, headersNoToken, loginCredentials.encode(),
      400, "Missing Okapi token header");
  }

  private Future<WrappedResponse> doLoginBadUserResponse(TestContext context, JsonObject loginCredentials) {
    return doRequest(vertx, loginUrl, HttpMethod.POST, headers, loginCredentials.encode(),
      422, "Error verifying user existence: Error, missing field(s) 'totalRecords' and/or 'users' in user response object");
  }

  private Future<WrappedResponse> doLoginMultiUserRespose(TestContext context, JsonObject loginCredentials) {
    return doRequest(vertx, loginUrl, HttpMethod.POST, headers, loginCredentials.encode(),
      422, "Error verifying user existence: Error, missing field(s) 'totalRecords' and/or 'users' in user response object");
  }

  private Future<WrappedResponse> doLoginNonExistentUser(TestContext context, JsonObject loginCredentials) {
    return doRequest(vertx, loginUrl, HttpMethod.POST, headers, loginCredentials.encode(),
      422, "Error verifying user existence: Error, missing field(s) 'totalRecords' and/or 'users' in user response object");
  }

  private Future<WrappedResponse> doLoginEmptyUserResponse(TestContext context, JsonObject loginCredentials) {
    return doRequest(vertx, loginUrl, HttpMethod.POST, headers, loginCredentials.encode(),
      422, "Error verifying user existence: Bad results from username");
  }

  private Future<WrappedResponse> doInactiveLogin(TestContext context, JsonObject loginCredentials) {
    return doRequest(vertx, loginUrl, HttpMethod.POST, headers, loginCredentials.encode(),
      422, "Fail login with inactive credentials");
  }

  private Future<WrappedResponse> doBadPasswordLogin(TestContext context, JsonObject loginCredentials) {
    return doRequest(vertx, loginUrl, HttpMethod.POST, headers, loginCredentials.encode(),
      422, "Fail login with bad credentials");
  }

  private Future<WrappedResponse> doUpdatePassword(TestContext context, JsonObject updateCredentials) {
    return doRequest(vertx, updateUrl, HttpMethod.POST, headers, updateCredentials.encode(),
      204, "Update existing credentials");
  }

  private Future<WrappedResponse> doBadCredentialsUpdatePassword(TestContext context, JsonObject updateCredentials) {
    return doRequest(vertx, updateUrl, HttpMethod.POST, headers, updateCredentials.encode(),
      401, "Attempt to update password with bad credentials");
  }

  private Future<WrappedResponse> doBadInputUpdatePassword(TestContext context, JsonObject updateCredentials) {
    return doRequest(vertx, updateUrl, HttpMethod.POST, headers, updateCredentials.encode(),
      400, "Attempt to update password with bad data");
  }
}

