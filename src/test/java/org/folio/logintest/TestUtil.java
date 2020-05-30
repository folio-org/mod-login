package org.folio.logintest;

import java.util.Map;

import org.folio.util.WebClientFactory;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;

/**
 *
 * @author kurt
 */
public class TestUtil {
  static class WrappedResponse {
    private String explanation;
    private int code;
    private String body;
    private JsonObject json;
    private HttpResponse<Buffer> response;

    public WrappedResponse(String explanation, int code, String body,
        HttpResponse<Buffer> response) {
      this.explanation = explanation;
      this.code = code;
      this.body = body;
      this.response = response;
      try {
        json = new JsonObject(body);
      } catch(Exception e) {
        json = null;
      }
    }

    public String getExplanation() {
      return explanation;
    }

    public int getCode() {
      return code;
    }

    public String getBody() {
      return body;
    }

    public HttpResponse<Buffer> getResponse() {
      return response;
    }

    public JsonObject getJson() {
      return json;
    }
  }

  public static Future<WrappedResponse> doRequest(Vertx vertx, String url,
          HttpMethod method, MultiMap headers, String payload,
          Integer expectedCode, String explanation) {
    Promise<WrappedResponse> promise = Promise.promise();
    WebClient client = WebClientFactory.getWebClient(vertx);
    HttpRequest<Buffer> request = client.requestAbs(method, url);
    //Add standard headers
    request.putHeader("X-Okapi-Tenant", "diku")
            .putHeader("content-type", "application/json")
            .putHeader("accept", "application/json");
    if(headers != null) {
      for(Map.Entry<?,?> entry : headers.entries()) {
        request.putHeader((String)entry.getKey(), (String)entry.getValue());
        System.out.println(String.format("Adding header '%s' with value '%s'",
            (String)entry.getKey(), (String)entry.getValue()));
      }
    }
    System.out.println("Sending " + method.toString() + " request to url '"+
        url + " with payload: " + payload + "'\n");
    if(method == HttpMethod.PUT || method == HttpMethod.POST) {
      request.sendBuffer(Buffer.buffer(payload), ar -> handleResponse(ar, method, url, explanation, expectedCode, promise));
    } else {
      request.send(ar -> handleResponse(ar, method, url, explanation, expectedCode, promise));
    }
    return promise.future();
  }

  private static void handleResponse(AsyncResult<HttpResponse<Buffer>> ar, HttpMethod method, String url, String explanation,
      Integer expectedCode, Promise<WrappedResponse> promise) {
    if (ar.failed()) {
      promise.fail(ar.cause());
    } else {
      HttpResponse<Buffer> res = ar.result();
      String explainString = explanation != null ? explanation : "(no explanation)";

      String body = res.bodyAsString();
      int status = res.statusCode();

      if (expectedCode != null && expectedCode != status) {
        promise.fail(method.toString() + " to " + url + " failed. Expected status code " + expectedCode + ", got status code "
            + status + ": " + body + " | " + explainString);
      } else {
        System.out.println("Got status code " + res.statusCode() + " with payload of: " + body + " | " + explainString);
        WrappedResponse wr = new WrappedResponse(explanation, status, body, res);
        promise.complete(wr);
      }
    }
  }
}
