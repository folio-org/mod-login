/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.folio.auth.login_module;

import org.folio.auth.login_module.impl.DummyAuthSource;
import org.folio.auth.login_module.impl.MongoAuthSource;
import org.folio.auth.login_module.impl.DummyUserSource;
import org.folio.auth.login_module.impl.ModuleUserSource;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import java.security.Key;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;

/**
 *
 * @author kurt
 */
public class MainVerticle extends AbstractVerticle {
  private AuthSource authSource = null;
  private AuthUtil authUtil;
  private MongoClient mongoClient;
  //private String okapiUrl;
  private String authApiKey;
  private static final String OKAPI_TOKEN_HEADER = "X-Okapi-Token";
  private static final String OKAPI_URL_HEADER = "X-Okapi-Url";
  private boolean verifyUsers;
  private UserSource userSource;
  private final Logger logger = LoggerFactory.getLogger("mod-auth-login-module");

  @Override
  public void start(Future<Void> future) {
    //authSource = new DummyAuthSource();
    authUtil = new AuthUtil();
    authApiKey = System.getProperty("auth.api.key", "VERY_WEAK_KEY");
    //okapiUrl = System.getProperty("okapi.url", "http://localhost:9130");
    
    String mongoURL = System.getProperty("mongo.url", "mongodb://localhost:27017/test");
    mongoClient = MongoClient.createShared(vertx, new JsonObject().put("connection_string", mongoURL));
    verifyUsers = Boolean.parseBoolean(System.getProperty("verify.users", "false"));
    authSource = new MongoAuthSource(mongoClient, authUtil);
    userSource = new DummyUserSource();
    
    String logLevel = System.getProperty("log.level", null);
    if(logLevel != null) {
      try {
        org.apache.log4j.Logger l4jLogger;
        l4jLogger = org.apache.log4j.Logger.getLogger("mod-auth-login-module");
        l4jLogger.getParent().setLevel(org.apache.log4j.Level.toLevel(logLevel));
      } catch(Exception e) {
        logger.error("Unable to set log level: " + e.getMessage());
      }
    }
    
    String signThis = System.getProperty("sign.this", null);
    if(signThis != null) {
      String salt = authUtil.getSalt();
      String hash = authUtil.calculateHash(signThis, salt);
      System.out.println("{ \"password\" : \"" + signThis + "\", \"salt\" : \"" + salt + "\", \"hash\" : \"" + hash + "\" }");
      future.complete();
      vertx.close();
      return;      
    }
    
    Router router = Router.router(vertx);
    router.post("/authn/login").handler(BodyHandler.create()); //Allow us to read the POST data
    router.post("/authn/login").handler(this::handleLogin);
    router.route("/authn/users").handler(BodyHandler.create());
    router.post("/authn/users").handler(this::handleUser);
    router.get("/authn/users").handler(this::handleUser);
    router.put("/authn/users/:username").handler(this::handleUser);
    router.delete("/authn/users/:username").handler(this::handleUser);
    router.get("/authn/users/:username").handler(this::handleUser);
    
    HttpServer server = vertx.createHttpServer();
    final int port = Integer.parseInt(System.getProperty("port", "8081"));
    server.requestHandler(router::accept).listen(port, result -> {
        if(result.succeeded()) {
          future.complete();
        } else {
          future.fail(result.cause());
        }
    });  
  }
  
  private void handleLogin(RoutingContext ctx) {
    final String postContent = ctx.getBodyAsString();
    String requestToken = getRequestToken(ctx);
    String tenant = ctx.request().headers().get("X-Okapi-Tenant");
    String okapiUrl = getOkapiUrl(ctx);
    JsonObject json = null;
    try {
      json = new JsonObject(postContent);
    } catch(DecodeException dex) {
      ctx.response().setStatusCode(400);
      ctx.response().end("Unable to decode '" + postContent + "' as valid JSON");
      return;
    }
    logger.debug("Authenticating with Mongo");
    authSource.authenticate(json, tenant).setHandler( res -> {
      if(res.failed()) {
        ctx.response()
                .setStatusCode(403)
                .end("Bad credentials format: Must be JSON formatted with 'username' and 'password' fields");
        return;
      } else {
        if(verifyUsers) {
          ModuleUserSource moduleUserSource = new ModuleUserSource();
          moduleUserSource.setTenant(tenant);
          moduleUserSource.setVertx(vertx);
          moduleUserSource.setRequestToken(getRequestToken(ctx));
          moduleUserSource.setOkapiUrl(okapiUrl);
          userSource = moduleUserSource;
        }
        userSource.getUser(res.result().getUser()).setHandler(res2 -> {
          if(res2.failed()) {
            ctx.response()
                    .setStatusCode(500)
                    .end("Unable to verify user");
          } else if(!res2.result().userExists() || !res2.result().isActive()) {
            ctx.response()
                    .setStatusCode(403)
                    .end("Invalid user");
          } else {
            logger.debug("Checking AuthResult");
            AuthResult authResult = res.result();
            if(!authResult.getSuccess()) {
              ctx.response()
                      .setStatusCode(403)
                      .end("Invalid credentials");
            } else {
              //String token = Jwts.builder().setSubject(authResult.getUser()).signWith(JWTAlgorithm, JWTSigningKey).compact();
              logger.debug("Authentication successful, getting signed token for login");
              JsonObject payload = new JsonObject()
                      .put("sub", authResult.getUser()); 
              //TODO: Debug and handle failure case
              String tokenUrl = okapiUrl + "/token";
              //System.out.println("Attempting to fetch token from url " + tokenUrl);
              fetchToken(payload, tokenUrl, requestToken, tenant).setHandler(result -> {
                if(result.failed()) {
                   ctx.response()
                           .setStatusCode(500)
                           .end("Unable to create token");
                   logger.error("Fetching token failed due to " + result.cause().getMessage());
                } else {
                  String token = result.result();
                  ctx.response()
                        .putHeader(OKAPI_TOKEN_HEADER, token)
                        .setStatusCode(200)
                        .end(postContent); 
                }
              });
            }
          }
        });
      }
    });    
  }
  
  private void handleUser(RoutingContext ctx) {
    String tenant = ctx.request().headers().get("X-Okapi-Tenant");
    String requestBody = null;
    if(ctx.request().method() == HttpMethod.POST ||
            ctx.request().method() == HttpMethod.PUT) {
      requestBody = ctx.getBodyAsString();
    }
    if(ctx.request().method() == HttpMethod.POST) {
      JsonObject postData = new JsonObject(requestBody);
      JsonObject credentials = postData.getJsonObject("credentials");
      JsonObject metadata = postData.getJsonObject("metadata");
      authSource.addAuth(credentials, metadata, tenant).setHandler(res-> {
        if(!res.succeeded()) {
          ctx.response()
                  .setStatusCode(500)
                  .end("Unable to add user");
        } else {
          ctx.response()
                  .setStatusCode(201)
                  .end("Added user");
        }
      });
      
    } else if (ctx.request().method() == HttpMethod.PUT) {
      String username = ctx.request().getParam("username");
      JsonObject postData = new JsonObject(requestBody);
      JsonObject credentials = postData.getJsonObject("credentials");
      JsonObject metadata = postData.getJsonObject("metadata");
      if(!credentials.getString("username").equals(username)) {
        ctx.response()
                .setStatusCode(400)
                .end("Invalid user");
        return;
      }
      authSource.updateAuth(credentials, metadata, tenant).setHandler(res -> {
        if(!res.succeeded()) {
          ctx.response()
                  .setStatusCode(500)
                  .end("Unable to update user");
        } else {
          ctx.response()
                  .setStatusCode(200)
                  .end("Updated user");
        }
      });      
    } else if (ctx.request().method() == HttpMethod.DELETE) {
      String username = ctx.request().getParam("username");
      authSource.deleteAuth(username, tenant).setHandler(res -> {
        if(!res.succeeded()) {
          ctx.response()
                  .setStatusCode(500)
                  .end("Unable to remove user");
        } else {
          ctx.response()
                  .setStatusCode(200)
                  .end("Deleted user");
        }
      });
    } else if (ctx.request().method() == HttpMethod.GET) {
      String username = ctx.request().getParam("username");
      if(username == null) {
        //Get list of users
        authSource.getAuthList(tenant).setHandler(res -> {
          if(res.failed()) {
            ctx.response()
                    .setStatusCode(500)
                    .end("Error: " + res.cause().getLocalizedMessage());
          } else {
            JsonObject responseObject = new JsonObject()
                    .put("credentials", new JsonArray())
                    .put("total_records", res.result().size());
            for(Object o : res.result()) {
              responseObject.getJsonArray("credentials").add(o);
            }
            ctx.response()
                    .setStatusCode(200)
                    .end(responseObject.encode());
          }
        });
      } else {
        authSource.getAuth(username, tenant).setHandler(res -> {
          if(res.failed()) {
            ctx.response()
                    .setStatusCode(500)
                    .end("Error " + res.cause().getLocalizedMessage());
          } else if(res.result() == null) {
            ctx.response()
                    .setStatusCode(404)
                    .end("User does not exist");            
          } else {
            ctx.response()
                    .setStatusCode(200)
                    .end(res.result().encode());
          }
        });
      }
    } else {
      ctx.response()
              .setStatusCode(400)
              .end("Unsupported operation");
      return;
    }
  }
  /*
  Retrieve a token from our token generator...in this case, the Authorization
  module. We pass along a shared key, since this exists outside of the standard
  auth chain
  */
  private Future<String> fetchToken(JsonObject payload, String url, String requestToken, String tenant) {
    Future<String> future = Future.future();
    HttpClient client = vertx.createHttpClient();
    logger.debug("Attempting to request token from url " + url + " for claims " + payload.encode());
    logger.debug("Using token: Bearer " + requestToken);
    HttpClientRequest request = client.postAbs(url);
    request.putHeader("Authorization", "Bearer " + requestToken);
    request.putHeader("X-Okapi-Tenant", tenant);
    request.handler(result -> {
      if(result.statusCode() != 200) {
        future.fail("Got error " + result.statusCode() + " fetching token");
        logger.debug("Fetching token trace: " + result.getHeader("X-Okapi-Trace"));
        result.bodyHandler(buf -> {
          logger.debug("Output from token fetch is: " + buf.toString());
        });
      } else {
        //String token = result.getHeader("Authorization");
        String token = result.getHeader(OKAPI_TOKEN_HEADER);
        logger.debug("Fetched token: " + token);
        future.complete(token);
      }
    });
    request.end(new JsonObject().put("payload",payload).encode());
    return future;
  }
  
  private String getRequestToken(RoutingContext ctx) {
    String token = ctx.request().headers().get(OKAPI_TOKEN_HEADER);
    if(token == null) {
      return "";
    }
    return token;
  }  
  
  private String getOkapiUrl(RoutingContext ctx) {
    if(ctx.request().getHeader(OKAPI_URL_HEADER) != null) {
      return ctx.request().getHeader(OKAPI_URL_HEADER);
    }
    return "";
  }
}
