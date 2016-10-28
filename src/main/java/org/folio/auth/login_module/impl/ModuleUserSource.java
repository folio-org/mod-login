/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.folio.auth.login_module.impl;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.folio.auth.login_module.UserResult;
import org.folio.auth.login_module.UserSource;
import java.net.URLEncoder;
/**
 *
 * @author kurt
 */
public class ModuleUserSource implements UserSource {

  private String okapiUrl = null;
  private Vertx vertx = null;
  private String requestToken = null;
  private String tenant = null;
  private final Logger logger = LoggerFactory.getLogger("mod-auth-authentication-module");
  
  @Override
  public Future<UserResult> getUser(String username) {
    if(okapiUrl == null || vertx == null || requestToken == null || tenant == null) {
      throw new RuntimeException("You must call setOkapiUrl, setVertx, setRequestToken and setTenant before calling this method");
    }
    Future<UserResult> future = Future.future();
    HttpClientOptions options = new HttpClientOptions();
    options.setConnectTimeout(10);
    options.setIdleTimeout(10);
    HttpClient client = vertx.createHttpClient(options);
    JsonObject query = new JsonObject()
            .put("username", username);
    String requestUrl = null;
    try {
      requestUrl = okapiUrl + "/users/?query=" + URLEncoder.encode(query.encode(), "UTF-8");
    } catch(Exception e) {
      future.fail(e);
      return future;
    }
    logger.debug("Requesting userdata from URL at " + requestUrl);
    client.getAbs(requestUrl, res -> {
      if(res.statusCode() != 200) {
        future.fail("Got status code " + res.statusCode());
      } else {
        res.bodyHandler(buf -> {
          logger.debug("Got content from server: " + buf.toString());
          JsonObject result = buf.toJsonObject();
          if(result.getInteger("total_records") > 1) {
            future.fail("Not unique username");
          } else if(result.getInteger("total_records") == 0) {
            UserResult userResult = new UserResult(username, false);
            future.complete(userResult);
          } else {
            UserResult userResult = new UserResult(username, true, result.getJsonArray("users").getJsonObject(0).getBoolean("active"));
            future.complete(userResult);
          }
        });
      }
    })
            .putHeader("X-Okapi-Tenant", tenant)
            .putHeader("Authorization", "Bearer " + requestToken)
            .putHeader("Content-type", "application/json")
            .putHeader("Accept", "application/json")
            .end();
    return future;
  }
  
  public void setOkapiUrl(String okapiUrl) {
    this.okapiUrl = okapiUrl;
  }

  public void setVertx(Vertx vertx) {
    this.vertx = vertx;
  }

  public void setRequestToken(String requestToken) {
    this.requestToken = requestToken;
  }

  public void setTenant(String tenant) {
    this.tenant = tenant;
  }
}
