/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.folio.auth.login_module;

import io.vertx.core.json.JsonObject;

/**
 *
 * @author kurt
 */
public class AuthResult {
  private boolean success;
  private JsonObject meta;
  private String user;
  
  public AuthResult() {
    success = false;
    meta = new JsonObject();
    user = "";
  }
  
  public AuthResult(boolean success, String user, JsonObject meta) {
    this.success = success;
    this.user = user;
    this.meta = meta;
  }

  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
  }

  public JsonObject getMeta() {
    return meta;
  }

  public void setMeta(JsonObject meta) {
    this.meta = meta;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }
  public boolean getSuccess() {
    return success;
  }
}
