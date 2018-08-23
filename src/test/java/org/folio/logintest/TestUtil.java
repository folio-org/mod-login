package org.folio.logintest;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import java.util.Map;

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
    private HttpClientResponse response;

    public WrappedResponse(String explanation, int code, String body,
        HttpClientResponse response) {
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

    public HttpClientResponse getResponse() {
      return response;
    }

    public JsonObject getJson() {
      return json;
    }
  }

  public static Future<WrappedResponse> doRequest(Vertx vertx, String url,
          HttpMethod method, CaseInsensitiveHeaders headers, String payload,
          Integer expectedCode, String explanation) {
    Future<WrappedResponse> future = Future.future();
    boolean addPayLoad = false;
    HttpClient client = vertx.createHttpClient();
    HttpClientRequest request = client.requestAbs(method, url);
    //Add standard headers
    request.putHeader("X-Okapi-Tenant", "diku")
            .putHeader("content-type", "application/json")
            .putHeader("accept", "application/json");
    if(headers != null) {
      for(Map.Entry entry : headers.entries()) {
        request.putHeader((String)entry.getKey(), (String)entry.getValue());
        System.out.println(String.format("Adding header '%s' with value '%s'",
            (String)entry.getKey(), (String)entry.getValue()));
      }
    }
    //standard exception handler
    request.exceptionHandler(e -> { future.fail(e); });
    request.handler( req -> {
      req.bodyHandler(buf -> {
        String explainString = "(no explanation)";
        if(explanation != null) { explainString = explanation; }
        if(expectedCode != null && expectedCode != req.statusCode()) {
          future.fail(method.toString() + " to " + url + " failed. Expected status code "
                  + expectedCode + ", got status code " + req.statusCode() + ": "
                  + buf.toString() + " | " + explainString);
        } else {
          System.out.println("Got status code " + req.statusCode() + " with payload of: " + buf.toString() + " | " + explainString);
          WrappedResponse wr = new WrappedResponse(explanation, req.statusCode(), buf.toString(), req);
          future.complete(wr);
        }
      });
    });
    System.out.println("Sending " + method.toString() + " request to url '"+
              url + " with payload: " + payload + "'\n");
    if(method == HttpMethod.PUT || method == HttpMethod.POST) {
      request.end(payload);
    } else {
      request.end();
    }
    return future;
  }
}
