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
import java.util.List;
import java.util.Map;
import javax.ws.rs.core.Response;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.CredentialsListObject;
import org.folio.rest.jaxrs.model.LoginCredentials;
import org.folio.rest.jaxrs.resource.AuthnResource;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Offset;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.tools.utils.OutStream;
import org.folio.rest.tools.utils.TenantTool;
import org.folio.util.AuthUtil;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;

/**
 *
 * @author kurt
 */
public class LoginAPI implements AuthnResource {

  private static final String TABLE_NAME_CREDENTIALS = "auth_credentials";
  private static final String OKAPI_TENANT_HEADER = "x-okapi-tenant";
  private static final String USER_NAME_FIELD = "'username'";
  private static final String OKAPI_TOKEN_HEADER = "x-okapi-token";
  private static final String OKAPI_URL_HEADER = "x-okapi-url";
  private static final String CREDENTIAL_NAME_FIELD = "'username'";
  private AuthUtil authUtil = new AuthUtil();

  private final Logger logger = LoggerFactory.getLogger(LoginAPI.class);

  private CQLWrapper getCQL(String query, int limit, int offset){
    CQL2PgJSON cql2pgJson = new CQL2PgJSON(TABLE_NAME_CREDENTIALS + ".jsonb");
    return new CQLWrapper(cql2pgJson, query).setLimit(new Limit(limit)).setOffset(new Offset(offset));
  }

  private String getTenant(Map<String, String> headers) {
    return TenantTool.calculateTenantId(headers.get(OKAPI_TENANT_HEADER));
  };

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

  private Future<String> fetchToken(JsonObject payload, String tenant, String okapiURL, String requestToken, Vertx vertx) {
    Future<String> future = Future.future();
    HttpClient client = vertx.createHttpClient();
    HttpClientRequest request = client.postAbs(okapiURL + "/token");
    request.putHeader(OKAPI_TOKEN_HEADER, requestToken);
    request.putHeader(OKAPI_TENANT_HEADER, tenant);
    request.handler(response -> {
      if(response.statusCode() != 200) {
        future.fail("Got response " + response.statusCode() + " fetching token");
      } else {
        String token = response.getHeader(OKAPI_TOKEN_HEADER);
        logger.debug("Got token " + token + " from authz");
        future.complete(token);
      }
    });
    request.end(new JsonObject().put("payload", payload).encode());
    return future;
  }

  @Override
  public void postAuthnLogin(LoginCredentials entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        String okapiURL = okapiHeaders.get(OKAPI_URL_HEADER);
        String requestToken = okapiHeaders.get(OKAPI_TOKEN_HEADER);
        Future<Boolean> userVerified;
        Object verifyUser = RestVerticle.MODULE_SPECIFIC_ARGS.get("verify.user");
        if(verifyUser != null && verifyUser.equals("yes")) {
          userVerified = lookupUser(entity.getUsername(), tenantId, okapiURL, requestToken, vertxContext.owner());
        } else {
          userVerified = Future.succeededFuture(Boolean.TRUE);
        }
        userVerified.setHandler(verifyResult -> {
          if(verifyResult.failed()) {
            //Error!
          } else if(!verifyResult.result()) {
            //User isn't valid
          } else {
            //User's okay, let's try to login
            try {
              Criteria nameCrit = new Criteria();
              nameCrit.addField(CREDENTIAL_NAME_FIELD);
              nameCrit.setOperation("=");
              nameCrit.setValue(entity.getUsername());
              PostgresClient.getInstance(vertxContext.owner(), tenantId).get(
                      TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(nameCrit), true, getReply-> {
                if(getReply.failed()) {
                  logger.debug("Error in postgres get operation: " + getReply.cause().getLocalizedMessage());
                  asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainInternalServerError("Internal Server error")));
                } else {
                  List<Credential> credList = (List<Credential>)getReply.result()[0];
                  if(credList.size() < 1) {
                    logger.debug("No matching credentials found for " + entity.getUsername());
                    asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainBadRequest("No credentials match that login")));
                  } else {
                    Credential userCred = credList.get(0);
                    String testHash = authUtil.calculateHash(entity.getPassword(), userCred.getSalt());
                    if(userCred.getHash().equals(testHash)) {
                      JsonObject payload = new JsonObject()
                              .put("sub", userCred.getUsername());
                      Future<String> fetchTokenFuture;
                      Object fetchTokenFlag = RestVerticle.MODULE_SPECIFIC_ARGS.get("fetch.token");
                      if(fetchTokenFlag != null && fetchTokenFlag.equals("no")) {
                        fetchTokenFuture = Future.succeededFuture("dummytoken");
                      } else {
                        logger.debug("Fetching token from authz with payload " + payload.encode());
                        fetchTokenFuture = fetchToken(payload, tenantId, okapiURL, requestToken, vertxContext.owner());
                      }
                      fetchTokenFuture.setHandler(fetchTokenRes -> {
                        if(fetchTokenRes.failed()) {
                          logger.debug("Error fetching token: " + fetchTokenRes.cause().getLocalizedMessage());
                          asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainInternalServerError("Internal Server error")));
                        } else {
                          //Append token as header to result
                          String authToken = fetchTokenRes.result();
                          asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withJsonCreated(authToken, entity)));
                        }
                      });
                    } else {
                      logger.debug("Password does not match for username " + entity.getUsername());
                      asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainBadRequest("Bad credentials")));
                    }
                  }
                }
              });
              //Make sure this username isn't already added
            } catch(Exception e) {
              logger.debug("Error with postgresclient on postAuthnLogin: " + e.getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainInternalServerError("Internal Server error")));
            }
          }
        });
      });
    } catch(Exception e) {
      logger.debug("Error running on verticle for postAuthnLogin: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainInternalServerError("Internal Server error")));
    }
  }

  @Override
  public void getAuthnCredentials(int length, int start, String sortBy, String query, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    try {
       vertxContext.runOnContext(v -> {
         String tenantId = getTenant(okapiHeaders);
         String[] fieldList = {"*"};
         CQLWrapper cql = getCQL(query, length, start - 1);
         try {
           PostgresClient.getInstance(vertxContext.owner(), tenantId).get(
                   TABLE_NAME_CREDENTIALS, Credential.class, fieldList, cql, true, false, getReply -> {
             if(getReply.failed()) {
               logger.debug("Error in PostgresClient get operation " + getReply.cause().getLocalizedMessage());
               asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.withPlainInternalServerError("Internal Server error")));
             } else {
               CredentialsListObject credentialsListObject = new CredentialsListObject();
               List<Credential> credentialList = (List<Credential>)getReply.result()[0];
               credentialsListObject.setCredentials(credentialList);
               credentialsListObject.setTotalRecords(credentialList.size());
               asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.withJsonOK(credentialsListObject)));
             }
           });
         } catch(Exception e) {
           logger.debug("Error invoking Postgresclient: "+ e.getLocalizedMessage());
           asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.withPlainInternalServerError("Internal Server error")));
         }
       });
    } catch(Exception e) {
      logger.debug("Error running on vertx context: " + e.getLocalizedMessage());
      if(e.getCause() != null && e.getCause().getClass().getSimpleName().contains("CQLParseException")) {
        asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.withPlainBadRequest("CQL Parsing Error for '" + query + "': " +
                e.getLocalizedMessage())));
      } else {
        asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.withPlainInternalServerError("Internal Server error")));
      }
    }
  }

  @Override
  public void postAuthnCredentials(LoginCredentials entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        Criteria nameCrit = new Criteria();
        nameCrit.addField(USER_NAME_FIELD);
        nameCrit.setOperation("=");
        nameCrit.setValue(entity.getUsername());
        try {
          PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(nameCrit), true, getReply->{
            if(getReply.failed()) {
              logger.debug("Error in PostgresClient get method: " + getReply.cause().getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(PostAuthnCredentialsResponse.withPlainInternalServerError("Internal Server error")));
            } else {
              List<Credential> credList = (List<Credential>)getReply.result()[0];
              if(credList.size() > 0) {
                logger.debug("Error adding credentials for username " + entity.getUsername() + ": Username already exists");
                asyncResultHandler.handle(Future.succeededFuture(PostAuthnCredentialsResponse.withPlainBadRequest("Username " + entity.getUsername() + " already exists")));
              } else {
                Credential credential = new Credential();
                credential.setUsername(entity.getUsername());
                credential.setSalt(authUtil.getSalt());
                credential.setHash(authUtil.calculateHash(entity.getPassword(), credential.getSalt()));
                PostgresClient postgresClient = PostgresClient.getInstance(vertxContext.owner(), tenantId);
                postgresClient.startTx(beginTx -> {
                  try {
                    postgresClient.save(beginTx, TABLE_NAME_CREDENTIALS, credential, saveReply -> {
                      if(saveReply.failed()) {
                        logger.debug("Error saving new credential: " + saveReply.cause().getLocalizedMessage());
                        asyncResultHandler.handle(Future.succeededFuture(PostAuthnCredentialsResponse.withPlainInternalServerError("Internal Server error")));
                      } else {
                        final LoginCredentials loginCred = entity;
                        postgresClient.endTx(beginTx, end -> {
                          asyncResultHandler.handle(Future.succeededFuture(PostAuthnCredentialsResponse.withJsonCreated(loginCred)));
                        });
                      }
                    });
                  } catch(Exception e) {
                    logger.debug("Error with PostgresClient to save record: " + e.getLocalizedMessage());
                    asyncResultHandler.handle(Future.succeededFuture(PostAuthnCredentialsResponse.withPlainInternalServerError("Internal Server error")));
                  }
                });
              }
            }
          });
        } catch(Exception e) {
          logger.debug("Error from PostgresClient: " + e.getLocalizedMessage());
          asyncResultHandler.handle(Future.succeededFuture(PostAuthnCredentialsResponse.withPlainInternalServerError("Internal Server error")));
        }
      });
    } catch(Exception e) {
      logger.debug("Error running on vertx context: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(PostAuthnCredentialsResponse.withPlainInternalServerError("Internal Server error")));
    }
  }

  @Override
  public void putAuthnCredentialsByUsername(String username, LoginCredentials entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        Criteria nameCrit = new Criteria();
        nameCrit.addField(CREDENTIAL_NAME_FIELD);
        nameCrit.setOperation("=");
        nameCrit.setValue(username);
        try {
          PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(nameCrit),true, getReply -> {
            if(getReply.failed()) {
              logger.debug("PostgresClient get operation failed: " + getReply.cause().getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByUsernameResponse.withPlainInternalServerError("Internal Server error")));
            } else {
              List<Credential> credList = (List<Credential>)getReply.result()[0];
              if(credList.isEmpty()) {
                asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByUsernameResponse.withPlainNotFound("No credentials found")));
              } else {
                Credential cred = credList.get(0);
                String newSalt = authUtil.getSalt();
                String newHash = authUtil.calculateHash(entity.getPassword(), newSalt);
                cred.setHash(newHash);
                cred.setSalt(newSalt);
                try {
                  PostgresClient.getInstance(vertxContext.owner(), tenantId).update(TABLE_NAME_CREDENTIALS, cred, new Criterion(nameCrit), true, putReply -> {
                    if(putReply.failed()) {
                      logger.debug("Error with PostgresClient update operation: " + putReply.cause().getLocalizedMessage());
                      asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByUsernameResponse.withPlainInternalServerError("Internal Server error")));
                    } else {
                     asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByUsernameResponse.withJsonOK(entity)));
                    }
                  });
                } catch(Exception e) {
                  logger.debug("Error with PostgresClient: " + e.getLocalizedMessage());
                  asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByUsernameResponse.withPlainInternalServerError("Internal Server error")));
                }
              }
            }
          });
        } catch(Exception e) {
          logger.debug("Error with PostgresClient: " + e.getLocalizedMessage());
          asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByUsernameResponse.withPlainInternalServerError("Internal Server error")));
        }
      });
    } catch(Exception e) {
      logger.debug("Error running on vertx context: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByUsernameResponse.withPlainInternalServerError("Internal Server error")));
    }
  }
  @Override
  public void getAuthnCredentialsByUsername(String username, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        Criteria nameCrit = new Criteria();
        nameCrit.addField(CREDENTIAL_NAME_FIELD);
        nameCrit.setOperation("=");
        nameCrit.setValue(username);
        try {
          PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(nameCrit), true, getReply -> {
            if(getReply.failed()) {
              logger.debug("Error in PostgresClient get operation: " + getReply.cause().getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByUsernameResponse.withPlainInternalServerError("Internal Server error")));
            } else {
              List<Credential> credList = (List<Credential>)getReply.result()[0];
              if(credList.isEmpty()) {
                asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByUsernameResponse.withPlainNotFound("No credentials for username " + username + " found")));
              } else {
                asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByUsernameResponse.withJsonOK(credList.get(0))));
              }
            }
          });
        } catch(Exception e) {
          logger.debug("Error from PostgresClient " + e.getLocalizedMessage());
          asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByUsernameResponse.withPlainInternalServerError("Internal Server error")));
        }
      });
    } catch(Exception e) {
      logger.debug("Error running on vertx context: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByUsernameResponse.withPlainInternalServerError("Internal Server error")));
    }
  }


  @Override
  public void deleteAuthnCredentialsByUsername(String username, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        Criteria nameCrit = new Criteria();
        nameCrit.addField(CREDENTIAL_NAME_FIELD);
        nameCrit.setOperation("=");
        nameCrit.setValue(username);
        try {
          PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(nameCrit), true, getReply -> {
            if(getReply.failed()) {
              logger.debug("Error in PostgresClient get operation: " + getReply.cause().getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByUsernameResponse.withPlainInternalServerError("Internal Server error")));
            } else {
              List<Credential> credList = (List<Credential>)getReply.result()[0];
              if(credList.isEmpty()) {
                asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByUsernameResponse.withPlainNotFound("No credentials for username " + username + " found")));
              } else {
                try {
                  PostgresClient.getInstance(vertxContext.owner(), tenantId).delete(TABLE_NAME_CREDENTIALS, new Criterion(nameCrit), deleteReply-> {
                    if(deleteReply.failed()) {
                      logger.debug("Error in PostgresClient get operation: " + deleteReply.cause().getLocalizedMessage());
                      asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByUsernameResponse.withPlainInternalServerError("Internal Server error")));
                    } else {
                      asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByUsernameResponse.withPlainNoContent("")));
                    }
                   });
                } catch(Exception e) {
                  logger.debug("Error from PostgresClient: " + e.getLocalizedMessage());
                  asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByUsernameResponse.withPlainInternalServerError("Internal Server error")));
                }
              }
            }
          });
        } catch(Exception e) {
          logger.debug("Error from PostgresClient " + e.getLocalizedMessage());
          asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByUsernameResponse.withPlainInternalServerError("Internal Server error")));
        }
      });
    } catch(Exception e) {
      logger.debug("Error running on vertx context: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByUsernameResponse.withPlainInternalServerError("Internal Server error")));
    }
  }

}
