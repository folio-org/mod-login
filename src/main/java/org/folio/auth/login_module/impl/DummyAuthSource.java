/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.folio.auth.login_module.impl;

import org.folio.auth.login_module.AuthResult;
import org.folio.auth.login_module.AuthSource;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 *
 * @author kurt
 */
public class DummyAuthSource implements AuthSource {

  @Override
  public Future<AuthResult> authenticate(JsonObject credentials, String tenant) {
    AuthResult authResult = new AuthResult();
    authResult.setSuccess(true);
    authResult.setUser("dummy");
    return Future.succeededFuture(authResult);
  }  

  @Override
  public Future<Boolean> addAuth(JsonObject credentials, JsonObject metadata, String tenant) {
    return Future.succeededFuture(true);
  }

  @Override
  public Future<Boolean> updateAuth(JsonObject credentials, JsonObject metadata, String tenant) {
    return Future.succeededFuture(true);  
  }

  @Override
  public Future<Boolean> deleteAuth(String username, String tenant) {
    return Future.succeededFuture(true);
  }

  @Override
  public Future<JsonArray> getAuthList(String tenant) {
    return Future.succeededFuture(new JsonArray()); //To change body of generated methods, choose Tools | Templates.
  }

  @Override
  public Future<JsonObject> getAuth(String username, String tenant) {
    return Future.succeededFuture(new JsonObject()); 
  }
}
