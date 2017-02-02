/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.folio.auth.login_module.impl;

import org.folio.auth.login_module.AuthResult;
import org.folio.auth.login_module.AuthSource;
import org.folio.auth.login_module.AuthUtil;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

/**
 *
 * @author kurt
 */
public class MongoAuthSource implements AuthSource {
  private final MongoClient mongoClient;
  private AuthUtil authUtil;
  private final Logger logger = LoggerFactory.getLogger("mod-auth-login-module");
  
  public MongoAuthSource(MongoClient mongoClient, AuthUtil authUtil) {
    this.mongoClient = mongoClient;
    this.authUtil = authUtil;
  }

  @Override
  public Future<AuthResult> authenticate(JsonObject credentials, String tenant) {
    Future<AuthResult> future = Future.future();
    String username = credentials.getString("username");
    String password = credentials.getString("password");
    if(username == null || password == null) {
      return Future.failedFuture("Credentials must contain a username and password");
    }
    JsonObject query = new JsonObject()
            .put("username", username)
            .put("tenant", tenant);
    logger.debug("Calling MongoDB to retrieve credentials");
    mongoClient.find("credentials", query, res -> {
      if(res.succeeded() && !res.result().isEmpty()) {
        JsonObject user = res.result().get(0);
        String storedHash = user.getString("hash");
        String storedSalt = user.getString("salt");
        String calculatedHash = authUtil.calculateHash(password, storedSalt);
        if(calculatedHash.equals(storedHash)) {
          future.complete(new AuthResult(true, username, user.getJsonObject("metadata")));
          logger.debug("Future completed (good)");
        } else {
          future.complete(new AuthResult(false, username, user.getJsonObject("metadata")));
          logger.debug("Future completed (bad)");
        }
      } else {
        //username not found
        logger.error("No such user: " + username);
        future.complete(new AuthResult(false, username, null));
      }
      logger.debug("Lambda completed");
    });
    logger.debug("Returning");
    return future;
  }

  @Override
  public Future<Boolean> addAuth(JsonObject credentials, JsonObject metadata, String tenant) {
    Future<Boolean> future = Future.future();
    String username = credentials.getString("username");
    String password = credentials.getString("password");
    JsonObject query = new JsonObject()
            .put("username", username)
            .put("tenant", tenant);
    mongoClient.find("credentials", query, res -> {
      if(res.succeeded()) {
        //username already exists!
        future.complete(Boolean.FALSE);
      } else {
        String newSalt = authUtil.getSalt();
        String newHash = authUtil.calculateHash(username, password);
        JsonObject insert = new JsonObject()
                .put("username", username)
                .put("tenant", tenant)
                //.put("password", password)
                .put("hash", newHash)
                .put("salt", newSalt)
                .put("metadata", metadata);
        mongoClient.insert("credentials", insert, res2 -> {
          if(res2.succeeded()) {
            future.complete(Boolean.TRUE);
          } else {
            future.complete(Boolean.FALSE);
          }
        });     
      }
    });
    return future;
  }
  
  @Override
  public Future<Boolean> updateAuth(JsonObject credentials, JsonObject metadata, String tenant) {
    Future<Boolean> future = Future.future();
    String username = credentials.getString("username");
    JsonObject query = new JsonObject()
            .put("username", username)
            .put("tenant", tenant);
    JsonObject update = new JsonObject();
    String newSalt = authUtil.getSalt();
    if(credentials.containsKey("password")) {
      String password = credentials.getString("password");
      String newHash = authUtil.calculateHash(password, newSalt);
      update
            .put("salt", newSalt)
            .put("hash", newHash);
    }
    if(metadata != null) {
      update.put("metadata", metadata);
    }
    mongoClient.updateCollection("credentials", query, update, res -> {
      if(res.succeeded()) {
        future.complete(Boolean.TRUE);
      } else {
        future.complete(Boolean.FALSE);
      }
    });
    
    return future;
  }

  @Override
  public Future<Boolean> deleteAuth(String username, String tenant) {
    Future<Boolean> future = Future.future();
    JsonObject query = new JsonObject()
            .put("username", username)
            .put("tenant", tenant);
    mongoClient.removeDocument("credentials", query, res -> {
      if(res.succeeded()) {
        future.complete(Boolean.TRUE);
      } else {
        future.complete(Boolean.FALSE);
      }
    }); 
    return future;
  }

  @Override
  public Future<JsonArray> getAuthList(String tenant) {
    Future<JsonArray> future = Future.future();
    JsonObject query = new JsonObject()
            .put("tenant", tenant);
    mongoClient.find("credentials", query, res -> {
      if(res.failed()) {
        future.fail(res.cause());
      } else {
        JsonArray resultArray = new JsonArray();
        for(JsonObject jOb : res.result()) {
          resultArray.add(jOb);
        }
        future.complete(resultArray);
      }      
    });
    return future;
  }

  @Override
  public Future<JsonObject> getAuth(String username, String tenant) {
    Future<JsonObject> future = Future.future();
    JsonObject query = new JsonObject()
            .put("tenant", tenant)
            .put("username", username);
    mongoClient.find("credentials", query, res -> {
      if(res.failed()) {
        future.fail(res.cause());
      } else {
        if(res.result().isEmpty()) {
          future.complete(null);
        } else {
          future.complete(res.result().get(0));
        }
      }
    });
    return future;
  }
  
}
