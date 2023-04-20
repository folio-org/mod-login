package org.folio.services.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.rest.impl.LoginAPI;
import org.folio.services.UserService;
import org.folio.util.LoginConfigUtils;
import org.folio.util.PercentCodec;
import org.folio.util.StringUtil;
import org.folio.util.WebClientFactory;

import java.util.Map;

public class UserServiceImpl implements UserService {
  private static final String USER_TENANTS_URI_PATH = "/user-tenants";
  private static final String REQUEST_URL_TEMPLATE = "%s%s";
  private static final String USER_TENANT_GET_ERROR = "Error getting user-tenant record; username=%s; userId=%s; tenantId=%s: %s";
  private static final Logger logger = LogManager.getLogger();

  private final Vertx vertx;


  public UserServiceImpl(Vertx vertx) {
    this.vertx = vertx;
  }

  @Override
  public UserService lookupUser(String username, String userId, String tenant, final String okapiURL,
      String requestToken, Handler<AsyncResult<JsonObject>> asyncResultHandler) {
    final String requestURL = buildUserLookupURL(okapiURL, username, userId);
    HttpRequest<Buffer> request = WebClientFactory.getWebClient(vertx).getAbs(requestURL);
    request.putHeader(XOkapiHeaders.TENANT, tenant)
      .putHeader(XOkapiHeaders.TOKEN, requestToken);
    request
      .expect(ResponsePredicate.JSON)
      .send()
      .map(res -> extractUserFromLookupResponse(res, requestURL, username))
      .onComplete(ar -> {
        if (ar.failed()) {
          asyncResultHandler.handle(Future.failedFuture(ar.cause()));
        } else {
          asyncResultHandler.handle(Future.succeededFuture(ar.result()));
        }
      });
    return this;
  }

  @Override
  public UserService getUserTenants(String currentTenantId, String username, String userId,
      String requestedTenantId, JsonObject headers, Handler<AsyncResult<JsonArray>> asyncResultHandler) {
    try {
      Map<String,String> okapiHeaders = LoginConfigUtils.decodeJsonHeaders(headers);
      getUserTenants(okapiHeaders, currentTenantId, username, userId, requestedTenantId)
        .onComplete(ar -> {
          if (ar.failed()) {
            asyncResultHandler.handle(Future.failedFuture(ar.cause()));
          } else {
            asyncResultHandler.handle(Future.succeededFuture(ar.result()));
          }
        });
    } catch (Exception ex) {
      logger.error(ex.getMessage(), ex);
      asyncResultHandler.handle(Future.failedFuture(ex));
    }
    return this;
  }

  static String buildUserLookupURL(String okapiURL, String username, String userId) {
    String query;
    if (username != null) {
      query = "username==" + StringUtil.cqlEncode(username);
    } else {
      query = "id==" + StringUtil.cqlEncode(userId);
    }
    return okapiURL + "/users?query=" + PercentCodec.encode(query);
  }

  private JsonObject extractUserFromLookupResponse(HttpResponse<Buffer> res, String requestURL, String username)
      throws LoginAPI.UserLookupException {
    if (res.statusCode() != 200) {
      String message = "Error looking up user at url '" + requestURL + "' Expected status code 200, got '" +
        res.statusCode() + "' :" + res.bodyAsString();
      throw new LoginAPI.UserLookupException(message);
    }
    JsonObject lookupResult = res.bodyAsJsonObject();
    if (!lookupResult.containsKey("totalRecords") || !lookupResult.containsKey("users")) {
      throw new LoginAPI.UserLookupException("Error, missing field(s) 'totalRecords' and/or 'users' in user response object");
    }
    int recordCount = lookupResult.getInteger("totalRecords");
    if (recordCount > 1) {
      throw new LoginAPI.UserLookupException("Bad results from username");
    }
    if (recordCount == 0) {
      throw new LoginAPI.UserLookupException("No user found by username " + username);
    }
    return lookupResult.getJsonArray("users").getJsonObject(0);
  }

  private Future<JsonArray> getUserTenants(Map<String,String> headers, String currentTenantId, String username,
      String userId, String requestedTenantId) {
    Map<String,String> okapiHeaders = new CaseInsensitiveMap<>(headers);
    String okapiUrl = okapiHeaders.get(XOkapiHeaders.URL);
    String okapiToken = okapiHeaders.get(XOkapiHeaders.TOKEN);
    String requestUrl = String.format(REQUEST_URL_TEMPLATE, okapiUrl, USER_TENANTS_URI_PATH);

    HttpRequest<Buffer> request = WebClientFactory.getWebClient(vertx).getAbs(requestUrl);
    if (userId != null) {
      request.addQueryParam("userId", userId);
    }
    if (username != null) {
      request.addQueryParam("username", username);
    }
    if (requestedTenantId != null) {
      request.addQueryParam("tenantId", requestedTenantId);
    }

    return request.putHeader(XOkapiHeaders.TOKEN, okapiToken)
      .putHeader(XOkapiHeaders.TENANT, currentTenantId)
      .send()
        .map(response -> response.bodyAsJsonObject().getJsonArray("userTenants"))
        .recover(e -> {
          String message = String.format(USER_TENANT_GET_ERROR, username, userId, requestedTenantId, e.getMessage());
          logger.info("{}", message, e);
          return Future.failedFuture(message);
        });
  }
}
