/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.folio.auth.login_module;

/**
 *
 * @author kurt
 */
public class UserResult {
  private String username;
  private boolean exists;

  public String getUsername() {
    return username;
  }

  public boolean userExists() {
    return exists;
  }

  public boolean isActive() {
    return active;
  }
  private boolean active;
  
  public UserResult(String username) {
    this.username = username;
    this.exists = true;
    this.active = true;
  }
  
  public UserResult(String username, boolean exists) {
    this.username = username;
    this.exists = exists;
    this.active = exists; //If it doesn't exist, it can't be active
  }
  
  public UserResult(String username, boolean exists, boolean active) {
    this.username = username;
    this.exists = exists;
    this.active = active;
  }
  
}
