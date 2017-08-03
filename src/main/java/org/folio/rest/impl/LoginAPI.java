package org.folio.rest.impl;

import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.Response;

import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.CredentialsListObject;
import org.folio.rest.jaxrs.model.LoginCredentials;
import org.folio.rest.jaxrs.resource.AuthnResource;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Offset;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.tools.utils.TenantTool;
import org.folio.rest.tools.utils.ValidationHelper;
import org.folio.util.AuthUtil;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;

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
  private static final String CREDENTIAL_USERID_FIELD = "'userId'";
  private static final String CREDENTIAL_ID_FIELD = "'id'";
  private AuthUtil authUtil = new AuthUtil();
  private boolean suppressErrorResponse = false;

  private final Logger logger = LoggerFactory.getLogger(LoginAPI.class);

  private String getErrorResponse(String response) {
    if(suppressErrorResponse) {
      return "Internal Server Error: Please contact Admin";
    }
    return response;
  }
  private CQLWrapper getCQL(String query, int limit, int offset) throws org.z3950.zing.cql.cql2pgjson.FieldException {
    CQL2PgJSON cql2pgJson = new CQL2PgJSON(TABLE_NAME_CREDENTIALS + ".jsonb");
    return new CQLWrapper(cql2pgJson, query).setLimit(new Limit(limit)).setOffset(new Offset(offset));
  }

  private String getTenant(Map<String, String> headers) {
    return TenantTool.calculateTenantId(headers.get(OKAPI_TENANT_HEADER));
  };

  //Query the mod-users module to determine whether or not the username is valid for login
  private Future<JsonObject> lookupUser(String username, String tenant, String okapiURL, String requestToken, Vertx vertx) {
    Future<JsonObject> future = Future.future();
    HttpClientOptions options = new HttpClientOptions();
    options.setConnectTimeout(10);
    options.setIdleTimeout(10);
    HttpClient client = vertx.createHttpClient(options);
    String requestURL = null;
    try {
      requestURL = okapiURL + "/users?query=" + URLEncoder.encode("username==" + username, "UTF-8");
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
          try {
            JsonObject resultObject = buf.toJsonObject();
            int recordCount = resultObject.getInteger("totalRecords");
            if(recordCount > 1) {
              future.fail("Bad results from username");
            } else if(recordCount == 0) {
              logger.debug("No user found by username " + username);
              future.fail("No user found by username " + username);
            } else {
              boolean active = resultObject.getJsonArray("users").getJsonObject(0).getBoolean("active");
              if(!active) {
                logger.debug("User " + username + " is inactive");
              } 
              future.complete(resultObject.getJsonArray("users").getJsonObject(0));
            }
          } catch(Exception e) {
            future.fail(e);
          }
        });
      }
    });
    request.end();
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
        Future<JsonObject> userVerified;
        userVerified = lookupUser(entity.getUsername(), tenantId, okapiURL, requestToken, vertxContext.owner());
        userVerified.setHandler(verifyResult -> {
          if(verifyResult.failed()) {
            String errMsg = "Error verifying user existence: " + verifyResult.cause().getLocalizedMessage();
            logger.debug(errMsg);
            asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainInternalServerError(getErrorResponse(errMsg))));
            //Error!
          } else if(!verifyResult.result().isEmpty() && !verifyResult.result().getBoolean("active")) {
            asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainBadRequest("User is missing or inactive")));
            //User isn't valid
          } else {
            //User's okay, let's try to login
            try {
              JsonObject userObject = verifyResult.result();
              Criteria useridCrit = new Criteria();
              useridCrit.addField(CREDENTIAL_USERID_FIELD);
              useridCrit.setOperation("=");
              useridCrit.setValue(userObject.getString("id"));
              PostgresClient.getInstance(vertxContext.owner(), tenantId).get(
                      TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(useridCrit), true, getReply-> {
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
                              .put("sub", userObject.getString("username"));
                      if(!userObject.isEmpty()) {
                        payload.put("user_id", userObject.getString("id"));
                      }
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
                          String errMsg = "Error fetching token: " + fetchTokenRes.cause().getLocalizedMessage();
                          logger.debug(errMsg);
                          asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainInternalServerError(getErrorResponse(errMsg))));
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
         try {
           CQLWrapper cql = getCQL(query, length, start - 1);
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
        String okapiURL = okapiHeaders.get(OKAPI_URL_HEADER);
        Future<JsonObject> userVerifyFuture = lookupUser(entity.getUser(),
            tenantId, okapiURL);
        userVerifyFuture.setHandler(verifyRes -> {
          if(verifyRes.failed()) {
            String message = "Error looking up user: " + verifyRes.cause().getLocalizedMessage();
            logger.error(message, verifyRes.cause());
            asyncResultHandler.handle(Future.succeededFuture(PostAuthnCredentialsResponse.withPlainBadRequest(message)));
          } else {
            JsonObject userOb = verifyRes.result();
            Criteria userIdCrit = new Criteria();
            userIdCrit.addField(CREDENTIAL_USERID_FIELD);
            userIdCrit.setOperation("=");
            userIdCrit.setValue(userOb.getString("id"));
            try {
              PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_CREDENTIALS, Credential.class,
                  new Criterion(userIdCrit), true, getCredReply -> {
                    if(getCredReply.failed()) {
                    } else {
                      List<Credential> credList = (List<Credential>)getCredReply.result()[0];
                      if(credList.size() > 0) {
                        //Error, this Credential already exists
                      } else {
                        //Now we can create a new Credential
                        Credential credential = new Credential();
                        credential.setUserId(userOb.getString("id"));
                        credential.setSalt(authUtil.getSalt());
                        credential.setHash(authUtil.calculateHash(entity.getPassword(), credential.getSalt()));
                        //And save it
                        PostgresClient pgClient = PostgresClient.getInstance(vertxContext.owner(), tenantId);
                        pgClient.startTx(beginTx -> {
                          try {
                            pgClient.save(beginTx, TABLE_NAME_CREDENTIALS,
                                credential, saveReply -> {
                             if(saveReply.failed()) {
                               String message = "Saving record failed: " + saveReply.cause().getLocalizedMessage();
                               logger.error(message, saveReply.cause());
                               asyncResultHandler.handle(Future.succeededFuture(
                                     PostAuthnCredentialsResponse.withPlainInternalServerError(message)));
                             } else {
                               asyncResultHandler.handle(Future.succeededFuture(
                                     PostAuthnCredentialsResponse.withJsonCreated(credential)));
                             }
                            });
                          } catch(Exception e) {
                            String message = e.getLocalizedMessage();
                            logger.error(message, e);
                            asyncResultHandler.handle(Future.succeededFuture(
                              PostAuthnCredentialsResponse.withPlainInternalServerError(message)));
                          }
                        });
                      }
                    }
              });
            } catch(Exception e) {
              String message = e.getLocalizedMessage();
              logger.error(message, e);
              asyncResultHandler.handle(Future.succeededFuture(
                    PostAuthnCredentialsResponse.withPlainInternalServerError(message)));
            }
          }
        });
      });
    } catch(Exception e) {
      logger.error("Error running on vertx context: " + e.getLocalizedMessage());
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
  public void getAuthnCredentialsById(String id, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        Criteria idCrit = new Criteria();
        idCrit.addField(CREDENTIAL_ID_FIELD);
        idCrit.setOperation("=");
        idCrit.setValue(username);
        try {
          PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(idCrit), true, getReply -> {
            if(getReply.failed()) {
              logger.debug("Error in PostgresClient get operation: " + getReply.cause().getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.withPlainInternalServerError("Internal Server error")));
            } else {
              List<Credential> credList = (List<Credential>)getReply.result()[0];
              if(credList.isEmpty()) {
                asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.withPlainNotFound("No credentials for username " + username + " found")));
              } else {
                asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.withJsonOK(credList.get(0))));
              }
            }
          });
        } catch(Exception e) {
          logger.debug("Error from PostgresClient " + e.getLocalizedMessage());
          asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.withPlainInternalServerError("Internal Server error")));
        }
      });
    } catch(Exception e) {
      logger.debug("Error running on vertx context: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.withPlainInternalServerError("Internal Server error")));
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
