/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.folio.rest.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import java.net.URLEncoder;
import java.util.Map;
import javax.ws.rs.core.Response;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.LoginCredentials;
import org.folio.rest.jaxrs.resource.AuthnResource;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Offset;
import org.folio.rest.persist.cql.CQLWrapper;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;

/**
 *
 * @author kurt
 */
public class LoginAPI implements AuthnResource {
  
  private static final String TABLE_NAME_CREDENTIALS = "auth_credentials";
  private static final String OKAPI_TENANT_HEADER = "x-okapi-tenant";
  private static final String USER_NAME_FIELD = "'username'";
  private static final String OKAPI_TOKEN_HEADER = "x_okapi_token";
  
  private final Logger logger = LoggerFactory.getLogger(LoginAPI.class);
  
  private CQLWrapper getCQL(String query, int limit, int offset){
    CQL2PgJSON cql2pgJson = new CQL2PgJSON(TABLE_NAME_CREDENTIALS + ".jsonb");
    return new CQLWrapper(cql2pgJson, query).setLimit(new Limit(limit)).setOffset(new Offset(offset));
  }
  
  //Query the mod-users module to determine whether or not the username is valid for login
  private Future<Boolean> lookupUser(String username, String tenant, String okapiURL, String requestToken, Vertx vertx) {
    Future<Boolean> future = Future.future();
    HttpClientOptions options = new HttpClientOptions();
    options.setConnectTimeout(10);
    options.setIdleTimeout(10);
    HttpClient client = vertx.createHttpClient(options);
    String requestURL = null;
    try {
      requestURL = okapiURL + "/users/?query=" + URLEncoder.encode("username=" + username, "UTF-8");
    } catch(Exception e) {
      logger.debug("Error building request URL: " + e.getLocalizedMessage());
      future.fail(e);
      return future;
    }
    HttpClientRequest request = client.getAbs(requestURL);
    request.putHeader(OKAPI_TENANT_HEADER, tenant)
            .putHeader(OKAPI_TOKEN_HEADER, requestToken)
            .putHeader("Content-type", "application/json")
            .putHeader("Accept", "application/json");
    request.handler(res -> {
      if(res.statusCode() != 200) {
        future.fail("Got status code " + res.statusCode());
      } else {
        res.bodyHandler(buf -> {
          JsonObject resultObject = buf.toJsonObject();
          if(resultObject.getInteger("total_records") > 1) {
            future.fail("Bad results from username");
          } else if(resultObject.getInteger("total_records") == 0) {
            logger.debug("No user found by username " + username);
            future.complete(Boolean.FALSE);
          } else {
            boolean active = resultObject.getJsonArray("users").getJsonObject(0).getBoolean("active");
            future.complete(active);
            if(!active) {
              logger.debug("User " + username + " is inactive");
            }
          }           
        });
      }
    });
    return future;
  }

  @Override
  public void postAuthnLogin(LoginCredentials entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  @Override
  public void getAuthnUsers(int length, int start, String sortBy, String query, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  @Override
  public void postAuthnUsers(Credential entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  @Override
  public void getAuthnUsersByUsername(String username, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  @Override
  public void putAuthnUsersByUsername(String username, Credential entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  @Override
  public void deleteAuthnUsersByUsername(String username, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }
  
}
