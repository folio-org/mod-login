package org.folio.logintest;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.folio.logintest.TestUtil.WrappedResponse;
import org.folio.rest.RestVerticle;
import org.folio.rest.client.TenantClient;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

import java.net.HttpURLConnection;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.folio.logintest.TestUtil.doRequest;
import static org.folio.logintest.UserMock.bombadilId;
import static org.folio.logintest.UserMock.gollumId;
import static org.folio.logintest.UserMock.sarumanId;
import org.folio.rest.jaxrs.model.Parameter;
import org.folio.rest.jaxrs.model.TenantAttributes;

@RunWith(VertxUnitRunner.class)
public class RestVerticleTest {

  private static final String SUPPORTED_CONTENT_TYPE_JSON_DEF = "application/json";

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


  private static String postCredsRequest = "{\"username\": \"gollum\", \"userId\":\"" + gollumId + "\", \"password\":\"12345\"}";
  private static String postCredsRequest2 = "{\"username\": \"gollum\", \"password\":\"12345\"}";
  private static String postCredsRequest3 = "{\"username\": \"saruman\", \"userId\":\"" + sarumanId + "\", \"password\":\"12345\"}";
  private static String postCredsRequest4 = "{\"username\": \"saruman\", \"password\":\"12345\"}";
  private static String postCredsRequest5 = "{\"username\": \"bombadil\", \"userId\":\"" + bombadilId + "\", \"password\":\"12345\"}";
  private static String postCredsRequest6 = "{\"username\": \"bombadil\", \"password\":\"12345\"}";

  private static Vertx vertx;
  private static int port;
  private static int mockPort;
  private static CaseInsensitiveHeaders headers;

  private final Logger logger = LoggerFactory.getLogger(RestVerticleTest.class);
  private static String credentialsUrl;
  private static String attemptsUrl;
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
    attemptsUrl = "http://localhost:" + port + "/authn/loginAttempts";
    loginUrl = "http://localhost:" + port + "/authn/login";
    updateUrl = "http://localhost:" + port + "/authn/update";
    okapiUrl = "http://localhost:" + mockPort;

    headers = new CaseInsensitiveHeaders();
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
            TenantAttributes ta = new TenantAttributes();
            ta.setModuleTo("mod-login-1.0.0");
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
    chainedFuture.setHandler(chainedRes -> {
      if (chainedRes.failed()) {
        logger.error("Test failed: " + chainedRes.cause().getLocalizedMessage());
        context.fail(chainedRes.cause());
      } else {
        async.complete();
      }
    });
  }

  //@Test
  public void testGroup(TestContext context) {
    String url = "http://localhost:" + port + "/authn/credentials";
    try {
      String credentialsId = null;

      /**add creds */
      CompletableFuture<Response> addPUCF = new CompletableFuture();
      String addPUURL = url;
      send(addPUURL, context, HttpMethod.POST, postCredsRequest,
        SUPPORTED_CONTENT_TYPE_JSON_DEF, 201, new HTTPResponseHandler(addPUCF));
      Response addPUResponse = addPUCF.get(5, TimeUnit.SECONDS);
      credentialsId = addPUResponse.body.getString("id");
      if (credentialsId == null) {
        System.out.println("Null id status for body: " + addPUResponse.body.encode());
      }
      context.assertEquals(addPUResponse.code, HttpURLConnection.HTTP_CREATED);
      System.out.println("Status - " + addPUResponse.code + " at " +
        System.currentTimeMillis() + " for " + addPUURL);

      /**add same creds again 422 */
      CompletableFuture<Response> addPUCF2 = new CompletableFuture();
      String addPUURL2 = url;
      send(addPUURL2, context, HttpMethod.POST, postCredsRequest,
        SUPPORTED_CONTENT_TYPE_JSON_DEF, 422, new HTTPResponseHandler(addPUCF2));
      Response addPUResponse2 = addPUCF2.get(5, TimeUnit.SECONDS);
      context.assertEquals(addPUResponse2.code, 422);
      System.out.println(addPUResponse2.body +
        "\nStatus - " + addPUResponse2.code + " at " + System.currentTimeMillis() + " for "
        + addPUURL2);


      /**try to GET the recently created creds 200 */
      CompletableFuture<Response> addPUCF2_5 = new CompletableFuture();
      String addPUURL2_5 = url + "/" + credentialsId;
      send(addPUURL2_5, context, HttpMethod.GET, null,
        SUPPORTED_CONTENT_TYPE_JSON_DEF, 200, new HTTPResponseHandler(addPUCF2_5));
      Response addPUResponse2_5 = addPUCF2_5.get(5, TimeUnit.SECONDS);
      context.assertEquals(addPUResponse2_5.code, 200);
      System.out.println(addPUResponse2_5.body +
        "\nStatus - " + addPUResponse2_5.code + " at " + System.currentTimeMillis() + " for "
        + addPUURL2_5);

      /**login with creds 201 */
      CompletableFuture<Response> addPUCF3 = new CompletableFuture();
      String addPUURL3 = "http://localhost:" + port + "/authn/login";
      send(addPUURL3, context, HttpMethod.POST, postCredsRequest,
        SUPPORTED_CONTENT_TYPE_JSON_DEF, 201, new HTTPResponseHandler(addPUCF3));
      Response addPUResponse3 = addPUCF3.get(5, TimeUnit.SECONDS);
      context.assertEquals(addPUResponse3.code, 201);
      System.out.println(addPUResponse3.body +
        "\nStatus - " + addPUResponse3.code + " at " + System.currentTimeMillis() + " for "
        + addPUURL3);

      /* test mock user*/
      CompletableFuture<Response> addPUCF4 = new CompletableFuture();
      String addPUURL4 = "http://localhost:" + mockPort + "/users?query=username==gollum";
      send(addPUURL4, context, HttpMethod.GET, null,
        SUPPORTED_CONTENT_TYPE_JSON_DEF, 200, new HTTPResponseHandler(addPUCF4));
      Response addPUResponse4 = addPUCF4.get(5, TimeUnit.SECONDS);
      context.assertEquals(addPUResponse4.code, 200);
      System.out.println(addPUResponse4.body +
        "\nStatus - " + addPUResponse4.code + " at " + System.currentTimeMillis() + " for "
        + addPUURL4);


      // test mock user 404
      CompletableFuture<Response> addPUCF5 = new CompletableFuture();
      String addPUURL5 = "http://localhost:" + mockPort + "/users?query=username==bilbo";
      send(addPUURL5, context, HttpMethod.GET, null,
        SUPPORTED_CONTENT_TYPE_JSON_DEF, 404, new HTTPResponseHandler(addPUCF5));
      Response addPUResponse5 = addPUCF5.get(5, TimeUnit.SECONDS);
      context.assertEquals(addPUResponse5.code, 404);
      System.out.println(addPUResponse5.body +
        "\nStatus - " + addPUResponse5.code + " at " + System.currentTimeMillis() + " for "
        + addPUURL5);


      /**login with creds, no userid supplied, 201 */
      CompletableFuture<Response> addPUCF6 = new CompletableFuture();
      String addPUURL6 = "http://localhost:" + port + "/authn/login";
      send(addPUURL6, context, HttpMethod.POST, postCredsRequest2,
        SUPPORTED_CONTENT_TYPE_JSON_DEF, 201, new HTTPResponseHandler(addPUCF6));
      Response addPUResponse6 = addPUCF6.get(5, TimeUnit.SECONDS);
      context.assertEquals(addPUResponse6.code, 201);
      System.out.println(addPUResponse6.body +
        "\nStatus - " + addPUResponse6.code + " at " + System.currentTimeMillis() + " for "
        + addPUURL6);

      /*add creds for inactive user */
      CompletableFuture<Response> addPUCF7 = new CompletableFuture();
      String addPUURL7 = url;
      send(addPUURL7, context, HttpMethod.POST, postCredsRequest3,
        SUPPORTED_CONTENT_TYPE_JSON_DEF, 201, new HTTPResponseHandler(addPUCF7));
      Response addPUResponse7 = addPUCF7.get(5, TimeUnit.SECONDS);
      credentialsId = addPUResponse7.body.getString("id");
      context.assertEquals(addPUResponse7.code, HttpURLConnection.HTTP_CREATED);
      System.out.println("Status - " + addPUResponse7.code + " at " +
        System.currentTimeMillis() + " for " + addPUURL7);

      /* try to login with inactive user */
      CompletableFuture<Response> addPUCF8 = new CompletableFuture();
      String addPUURL8 = "http://localhost:" + port + "/authn/login";
      send(addPUURL8, context, HttpMethod.POST, postCredsRequest4,
        SUPPORTED_CONTENT_TYPE_JSON_DEF, 400, new HTTPResponseHandler(addPUCF8));
      Response addPUResponse8 = addPUCF8.get(5, TimeUnit.SECONDS);
      context.assertEquals(addPUResponse8.code, 400);
      System.out.println(addPUResponse8.body +
        "\nStatus - " + addPUResponse8.code + " at " + System.currentTimeMillis() + " for "
        + addPUURL8);

      /*add creds for slow lookup user */
      CompletableFuture<Response> addPUCF9 = new CompletableFuture();
      String addPUURL9 = url;
      send(addPUURL9, context, HttpMethod.POST, postCredsRequest5,
        SUPPORTED_CONTENT_TYPE_JSON_DEF, 201, new HTTPResponseHandler(addPUCF9));
      Response addPUResponse9 = addPUCF9.get(5, TimeUnit.SECONDS);
      credentialsId = addPUResponse9.body.getString("id");
      context.assertEquals(addPUResponse9.code, HttpURLConnection.HTTP_CREATED);
      System.out.println("Status - " + addPUResponse9.code + " at " +
        System.currentTimeMillis() + " for " + addPUURL9);

      /* try to login with slow lookup user */
      CompletableFuture<Response> addPUCF10 = new CompletableFuture();
      String addPUURL10 = "http://localhost:" + port + "/authn/login";
      send(addPUURL10, context, HttpMethod.POST, postCredsRequest6,
        SUPPORTED_CONTENT_TYPE_JSON_DEF, 200, new HTTPResponseHandler(addPUCF10));
      Response addPUResponse10 = addPUCF10.get(5, TimeUnit.SECONDS);
      context.assertEquals(addPUResponse10.code, 201);
      System.out.println(addPUResponse10.body +
        "\nStatus - " + addPUResponse10.code + " at " + System.currentTimeMillis() + " for "
        + addPUURL10);


    } catch (Exception e) {
      e.printStackTrace();
      context.fail(e.getMessage());
    }
  }

  private void send(String url, TestContext context, HttpMethod method, String content,
                    String contentType, int errorCode, Handler<HttpClientResponse> handler) {
    HttpClient client = vertx.createHttpClient();
    HttpClientRequest request;
    if (content == null) {
      content = "";
    }
    Buffer buffer = Buffer.buffer(content);

    if (method == HttpMethod.POST) {
      request = client.postAbs(url);
    } else if (method == HttpMethod.DELETE) {
      request = client.deleteAbs(url);
    } else if (method == HttpMethod.GET) {
      request = client.getAbs(url);
    } else {
      request = client.putAbs(url);
    }
    request.exceptionHandler(error -> {
      context.fail(error.getMessage());
    })
      .handler(handler);
    request.putHeader("Authorization", "diku");
    request.putHeader("x-okapi-tenant", "diku");
    request.putHeader("Accept", "application/json,text/plain");
    request.putHeader("Content-type", contentType);
    request.putHeader("X-Okapi-Url", "http://localhost:" + mockPort);
    request.putHeader("X-Okapi-Token", "dummytoken");
    request.end(buffer);
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

  private boolean isSizeMatch(Response r, int size) {
    return r.body.getInteger("total_records") == size;
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

