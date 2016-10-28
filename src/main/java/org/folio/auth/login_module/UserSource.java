/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.folio.auth.login_module;

import io.vertx.core.Future;

/**
 *
 * @author kurt
 */
public interface UserSource {
  
  public Future<UserResult> getUser(String username);
  
}
