/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.folio.auth.login_module;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 *
 * @author kurt
 */
public interface AuthSource {
  public Future<AuthResult> authenticate(JsonObject credentials, String tenant);
  public Future<Boolean> addAuth(JsonObject credentials, JsonObject metadata, String tenant);
  public Future<Boolean> updateAuth(JsonObject credentials, JsonObject metadata, String tenant);
  public Future<Boolean> deleteAuth(String username, String tenant);
}
