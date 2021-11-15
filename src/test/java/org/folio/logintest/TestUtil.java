package org.folio.logintest;

import java.util.Map;

import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.rest.impl.TenantAPI;
import org.folio.rest.impl.TenantRefAPI;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.util.WebClientFactory;

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
  private static final Logger logger = LogManager.getLogger(TestUtil.class);

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
  }

  public static Future<WrappedResponse> doRequest(Vertx vertx, String url,
          HttpMethod method, MultiMap headers, String payload,
          Integer expectedCode, String explanation) {
    WebClient client = WebClientFactory.getWebClient(vertx);
    HttpRequest<Buffer> request = client.requestAbs(method, url);
    //Add standard headers
    request.putHeader(XOkapiHeaders.TENANT, "diku")
            .putHeader("content-type", "application/json")
            .putHeader("accept", "application/json");
    if (headers != null) {
      for(Map.Entry<String,String> entry : headers.entries()) {
        request.putHeader(entry.getKey(), entry.getValue());
        logger.info("Adding header '{}' with value '{}'", entry.getKey(), entry.getValue());
      }
    }
    logger.info("Sending {} request to url '{} with payload: {}", method.toString(), url, payload);
    Future<HttpResponse<Buffer>> future =
        method == HttpMethod.PUT || method == HttpMethod.POST
            ? request.sendBuffer(Buffer.buffer(payload))
            : request.send();
    return future.compose(res -> handleResponse(res, method, url, explanation, expectedCode));
  }

  private static Future<WrappedResponse> handleResponse(HttpResponse<Buffer> res, HttpMethod method, String url,
      String explanation, Integer expectedCode) {

    String explainString = explanation != null ? explanation : "(no explanation)";
    String body = res.bodyAsString();
    int status = res.statusCode();

    if (expectedCode != null && expectedCode != status) {
      return Future.failedFuture(method.toString() + " to " + url + " failed. Expected status code " + expectedCode + ", got status code "
          + status + ": " + body + " | " + explainString);
    } else {
      logger.info("Got status code {} with payload of: {} | {}", res.statusCode(), body, explainString);
      return Future.succeededFuture(new WrappedResponse(explanation, status, body, res));
    }
  }

  public static Future<Void> postSync(TenantAttributes ta, String tenant, int port, Vertx vertx) {
    Map<String, String> okapiHeaders = new CaseInsensitiveMap<>();
    okapiHeaders.put(XOkapiHeaders.URL, "http://localhost:" + port);
    okapiHeaders.put(XOkapiHeaders.TENANT, tenant);
    TenantAPI tenantAPI = new TenantRefAPI();
    Promise<Void> promise = Promise.promise();
    tenantAPI.postTenantSync(ta, okapiHeaders, x -> promise.handle(x.mapEmpty()), vertx.getOrCreateContext());
    return promise.future();
  }
}
