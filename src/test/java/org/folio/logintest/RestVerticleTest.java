package org.folio.logintest;

import java.net.HttpURLConnection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.folio.rest.RestVerticle;
import org.folio.rest.client.TenantClient;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;

@RunWith(VertxUnitRunner.class)
public class RestVerticleTest {

  private static final String       SUPPORTED_CONTENT_TYPE_JSON_DEF = "application/json";
  private static final String       SUPPORTED_CONTENT_TYPE_TEXT_DEF = "text/plain";

  private static String postCredsRequest = "{\"username\": \"a\", \"password\":\"12345\"}";

  private static Vertx vertx;
  static int port;

  @Rule
  public Timeout rule = Timeout.seconds(180);  // 3 minutes for loading embedded postgres

  @BeforeClass
  public static void setup(TestContext context) {
    Async async = context.async();
    port = NetworkUtils.nextFreePort();
    TenantClient tenantClient = new TenantClient("localhost", port, "diku");
    vertx = Vertx.vertx();
    DeploymentOptions options = new DeploymentOptions().setConfig(new JsonObject().put("http.port", port));
    try {
      PostgresClient.setIsEmbedded(true);
      PostgresClient.getInstance(vertx).startEmbeddedPostgres();
    } catch(Exception e) {
      e.printStackTrace();
      context.fail(e);
      return;
    }
    vertx.deployVerticle(RestVerticle.class.getName(), options, res -> {
      try {
        tenantClient.post(null, res2 -> {
           async.complete();
        });
      } catch(Exception e) {
        e.printStackTrace();
      }

    });
  }

  @AfterClass
  public static void teardown(TestContext context) {
    Async async = context.async();
    vertx.close(context.asyncAssertSuccess( res-> {
      PostgresClient.stopEmbeddedPostgres();
      async.complete();
    }));
  }
  
  @Test
  public void testGroup(TestContext context){
    String url = "http://localhost:"+port+"/authn/credentials";
    try {
      /**add creds */
       CompletableFuture<Response> addPUCF = new CompletableFuture();
       String addPUURL = url;
       send(addPUURL, context, HttpMethod.POST, postCredsRequest,
         SUPPORTED_CONTENT_TYPE_JSON_DEF, 201,  new HTTPNoBodyResponseHandler(addPUCF));
       Response addPUResponse = addPUCF.get(5, TimeUnit.SECONDS);
       context.assertEquals(addPUResponse.code, HttpURLConnection.HTTP_CREATED);
       System.out.println("Status - " + addPUResponse.code + " at " +
           System.currentTimeMillis() + " for " + addPUURL);

       /**add same creds again 422 */
       CompletableFuture<Response> addPUCF2 = new CompletableFuture();
       String addPUURL2 = url;
       send(addPUURL2, context, HttpMethod.POST, postCredsRequest,
         SUPPORTED_CONTENT_TYPE_JSON_DEF, 422,  new HTTPResponseHandler(addPUCF2));
       Response addPUResponse2 = addPUCF2.get(5, TimeUnit.SECONDS);
       context.assertEquals(addPUResponse2.code, 422);
       System.out.println(addPUResponse2.body +
         "\nStatus - " + addPUResponse2.code + " at " + System.currentTimeMillis() + " for "
           + addPUURL2);
  

    } catch (Exception e) {
      e.printStackTrace();
      context.fail(e.getMessage());
    }
  }

 private void send(String url, TestContext context, HttpMethod method, String content,
     String contentType, int errorCode, Handler<HttpClientResponse> handler) {
   HttpClient client = vertx.createHttpClient();
   HttpClientRequest request;
   if(content == null){
     content = "";
   }
   Buffer buffer = Buffer.buffer(content);

   if (method == HttpMethod.POST) {
     request = client.postAbs(url);
   }
   else if (method == HttpMethod.DELETE) {
     request = client.deleteAbs(url);
   }
   else if (method == HttpMethod.GET) {
     request = client.getAbs(url);
   }
   else {
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
   request.end(buffer);
 }

 class HTTPResponseHandler implements Handler<HttpClientResponse> {

   CompletableFuture<Response> event;
   public HTTPResponseHandler(CompletableFuture<Response> cf){
     event = cf;
   }
   @Override
   public void handle(HttpClientResponse hcr) {
     hcr.bodyHandler( bh -> {
       Response r = new Response();
       r.code = hcr.statusCode();
       r.body = bh.toJsonObject();
       event.complete(r);
     });
   }
 }

 class HTTPNoBodyResponseHandler implements Handler<HttpClientResponse> {

   CompletableFuture<Response> event;
   public HTTPNoBodyResponseHandler(CompletableFuture<Response> cf){
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

 private boolean isSizeMatch(Response r, int size){
   if(r.body.getInteger("total_records") == size){
     return true;
   }
   return false;
 }

}

