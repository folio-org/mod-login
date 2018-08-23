package org.folio.rest.impl;

import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.Response;

import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.CredentialsListObject;
import org.folio.rest.jaxrs.model.LoginAttempts;
import org.folio.rest.jaxrs.model.LoginCredentialsJson;
import org.folio.rest.jaxrs.model.UpdateCredentialsJson;
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
import org.folio.util.OkapiConnectionParams;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;

import java.util.UUID;

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
import java.net.URL;

import java.util.MissingResourceException;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.util.LoginAttemptsHelper.LOGIN_ATTEMPTS_SCHEMA_PATH;
import static org.folio.util.LoginAttemptsHelper.TABLE_NAME_LOGIN_ATTEMPTS;
import static org.folio.util.LoginAttemptsHelper.onLoginFailAttemptHandler;
import static org.folio.util.LoginAttemptsHelper.onLoginSuccessAttemptHandler;
import static org.folio.util.LoginAttemptsHelper.getLoginAttemptsByUserId;
import static org.folio.util.LoginAttemptsHelper.buildCriteriaForUserAttempts;

import org.folio.rest.jaxrs.model.Errors;

/**
 *
 * @author kurt
 */
public class LoginAPI implements AuthnResource {

  private static final String TABLE_NAME_CREDENTIALS = "auth_credentials";
  public static final String OKAPI_TENANT_HEADER = "x-okapi-tenant";
  public static final String OKAPI_TOKEN_HEADER = "x-okapi-token";
  public static final String OKAPI_URL_HEADER = "x-okapi-url";
  private static final String CREDENTIAL_USERID_FIELD = "'userId'";
  private static final String CREDENTIAL_ID_FIELD = "'id'";
  private static final String CREDENTIAL_SCHEMA_PATH = "apidocs/raml-util/schemas/mod-login/credentials.json";
  private static final String POSTGRES_ERROR = "Error from PostgresClient ";
  public static final String INTERNAL_ERROR = "Internal Server error";
  private AuthUtil authUtil = new AuthUtil();
  private boolean suppressErrorResponse = false;
  private boolean requireActiveUser = Boolean.parseBoolean(MODULE_SPECIFIC_ARGS
      .getOrDefault("require.active", "true"));
  private int lookupTimeout = Integer.parseInt(MODULE_SPECIFIC_ARGS
      .getOrDefault("lookup.timeout", "1000"));

  private final Logger logger = LoggerFactory.getLogger(LoginAPI.class);

  private String getErrorResponse(String response) {
    if(suppressErrorResponse) {
      return "Internal Server Error: Please contact Admin";
    }
    return response;
  }
  private CQLWrapper getCQL(String query, int limit, int offset)
      throws org.z3950.zing.cql.cql2pgjson.FieldException {
    CQL2PgJSON cql2pgJson = new CQL2PgJSON(TABLE_NAME_CREDENTIALS + ".jsonb");
    return new CQLWrapper(cql2pgJson, query).setLimit(new Limit(limit)).setOffset(
        new Offset(offset));
  }

  private String getTenant(Map<String, String> headers) {
    return TenantTool.calculateTenantId(headers.get(OKAPI_TENANT_HEADER));
  };

  /*
    Query the mod-users module to determine whether or not the username is
    valid for login
  */
  private Future<JsonObject> lookupUser(String username, String userId, String tenant,
      final String okapiURL, String requestToken, Vertx vertx) {
    Future<JsonObject> future = Future.future();
    HttpClientOptions options = new HttpClientOptions();
    options.setConnectTimeout(lookupTimeout);
    options.setIdleTimeout(lookupTimeout);
    HttpClient client = vertx.createHttpClient(options);
    String requestURL = null;
    if(requestToken == null) {
      requestToken = "";
    }
    if(username == null && userId == null) {
      return Future.failedFuture("Need a valid username or userId to query");
    }
    try {
      if(username != null) {
        requestURL = String.format("%s/users?query=username==%s", okapiURL,
            URLEncoder.encode(username, "UTF-8"));
      } else {
        requestURL = String.format("%s/users?query=id==%s", okapiURL,
            URLEncoder.encode(userId, "UTF-8"));
      }
    } catch(Exception e) {
      logger.error("Error building request URL: " + e.getLocalizedMessage());
      future.fail(e);
      return future;
    }
    try {
      final String finalRequestURL = requestURL;
      HttpClientRequest request = client.getAbs(finalRequestURL);
      request.putHeader(OKAPI_TENANT_HEADER, tenant)
              .putHeader(OKAPI_TOKEN_HEADER, requestToken)
              .putHeader("Content-type", "application/json")
              .putHeader("Accept", "application/json");
      request.handler(res -> {
        if(res.statusCode() != 200) {
          res.bodyHandler(buf -> {
            String message = "Error looking up user at url '" + finalRequestURL
                + "' Expected status code 200, got '" + res.statusCode() +
                    "' :" + buf.toString();
            future.fail(message);
          });
        } else {
          res.bodyHandler(buf -> {
            try {
              JsonObject resultObject = buf.toJsonObject();
              if(!resultObject.containsKey("totalRecords") ||
                  !resultObject.containsKey("users")) {
                future.fail("Error, missing field(s) 'totalRecords' and/or 'users' in user response object");
              } else {
                int recordCount = resultObject.getInteger("totalRecords");
                if(recordCount > 1) {
                  future.fail("Bad results from username");
                } else if(recordCount == 0) {
                  logger.error("No user found by username " + username);
                  future.fail("No user found by username " + username);
                } else {
                  future.complete(resultObject.getJsonArray("users").getJsonObject(0));
                }
              }
            } catch(Exception e) {
              future.fail(e);
            }
          });
        }
      });
      request.setTimeout(lookupTimeout);
      request.exceptionHandler(e -> { future.fail(e); });
      request.end();
    } catch(Exception e) {
      String message = "User lookup failed at url '"+ requestURL +"': " + e.getLocalizedMessage();
      logger.error(message, e);
      future.fail(message);
    }
    return future;
  }

  private Future<String> fetchToken(JsonObject payload, String tenant,
      String okapiURL, String requestToken, Vertx vertx) {
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
    request.exceptionHandler(e -> {future.fail(e);});
    request.end(new JsonObject().put("payload", payload).encode());
    return future;
  }

  @Override
  public void getAuthnLoginAttemptsById(String id, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        try {
          testForFile(LOGIN_ATTEMPTS_SCHEMA_PATH);
          PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_LOGIN_ATTEMPTS, LoginAttempts.class, buildCriteriaForUserAttempts(id), true,  getReply -> {
            if(getReply.failed()) {
              logger.debug("Error in PostgresClient get operation: " + getReply.cause().getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
            } else {
              List<LoginAttempts> attemptsList = (List<LoginAttempts>)getReply.result().getResults();
              if(attemptsList.isEmpty()) {
                asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.withPlainNotFound("No user login attempts for id " + id + " found")));
              } else {
                asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.withJsonOK(attemptsList.get(0))));
              }
            }
          });
        } catch(Exception e) {
          logger.debug(POSTGRES_ERROR + e.getLocalizedMessage());
          asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
        }
      });
    } catch(Exception e) {
      logger.debug("Error running on vertx context: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
    }
  }

  @Override
  public void postAuthnLogin(LoginCredentialsJson entity, Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext)
      throws Exception {
    try {
      testForFile(CREDENTIAL_SCHEMA_PATH);
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        String okapiURL = okapiHeaders.get(OKAPI_URL_HEADER);
        String requestToken = okapiHeaders.get(OKAPI_TOKEN_HEADER);
        if(requestToken == null) {
          logger.error("Missing request token");
          asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
              .withPlainBadRequest("Missing Okapi token header")));
          return;
        }
        Future<JsonObject> userVerified;
        if(entity.getUserId() == null && entity.getUsername() == null) {
          logger.error("No username or userId provided for login attempt");
          asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
              .withPlainBadRequest("You must provide a username or userId")));
          return;
        }
        if(entity.getPassword() == null) {
          logger.error("No password provided for login attempt");
          asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
              .withPlainBadRequest("You must provide a password")));
          return;
        }
        if(entity.getUserId() != null && !requireActiveUser) {
          logger.debug("No need to look up user id");
          userVerified = Future.succeededFuture(new JsonObject()
              .put("id", entity.getUserId()).put("active", true)
              .put("username", "__undefined__"));
        } else {
          logger.debug("Need to look up user id");
          if(entity.getUserId() != null) {
            userVerified = lookupUser(null, entity.getUserId(), tenantId, okapiURL,
                requestToken, vertxContext.owner());
          } else {
            userVerified = lookupUser(entity.getUsername(), null, tenantId, okapiURL,
                requestToken, vertxContext.owner());
          }
        }
        userVerified.setHandler(verifyResult -> {
          if(verifyResult.failed()) {
            String errMsg = "Error verifying user existence: " + verifyResult
                .cause().getLocalizedMessage();
            logger.error(errMsg);
            asyncResultHandler.handle(Future.succeededFuture(
                PostAuthnLoginResponse.withPlainBadRequest(
                getErrorResponse(errMsg))));
          } else {
            //User's okay, let's try to login
            try {
              JsonObject userObject = verifyResult.result();
              if(!userObject.containsKey("id")) {
                logger.error("No 'id' key in returned user object");
                asyncResultHandler.handle(Future.succeededFuture(
                    PostAuthnLoginResponse.withPlainInternalServerError(
                    "No user id could be found")));
                return;
              }
              if(requireActiveUser) {
                boolean foundActive = false;
                if(userObject.containsKey("active") && userObject.getBoolean("active")) {
                  foundActive = true;
                }
                if(!foundActive) {
                  logger.error("User could not be verified as active");
                  asyncResultHandler.handle(Future.succeededFuture(
                    PostAuthnLoginResponse.withPlainBadRequest(
                    "User must be flagged as active")));
                  return;
                }
              }
              Criteria useridCrit = new Criteria(CREDENTIAL_SCHEMA_PATH);
              //Criteria useridCrit = new Criteria();
              useridCrit.addField(CREDENTIAL_USERID_FIELD);
              useridCrit.setOperation("=");
              useridCrit.setValue(userObject.getString("id"));
              PostgresClient.getInstance(vertxContext.owner(), tenantId).get(
                  TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(useridCrit),
                  true, getReply-> {
                if(getReply.failed()) {
                  logger.error("Error in postgres get operation: " +
                      getReply.cause().getLocalizedMessage());
                  asyncResultHandler.handle(Future.succeededFuture(
                      PostAuthnLoginResponse.withPlainInternalServerError(
                      INTERNAL_ERROR)));
                } else {
                  try {
                    List<Credential> credList = (List<Credential>)getReply.result().getResults();
                    if(credList.size() < 1) {
                      logger.error("No matching credentials found for userid " + userObject.getString("id"));
                      asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainBadRequest("No credentials match that login")));
                    } else {
                      Credential userCred = credList.get(0);
                      if(userCred.getHash() == null || userCred.getSalt() == null) {
                        String message = "Error retrieving stored hash and salt from credentials";
                        logger.error(message);
                        asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainInternalServerError(message)));
                        return;
                      }
                      logger.debug("Testing hash for credentials for user with id '" + userObject.getString("id") + "'");
                      String testHash = authUtil.calculateHash(entity.getPassword(), userCred.getSalt());
                      if(userCred.getHash().equals(testHash)) {
                        JsonObject payload = new JsonObject();
                        if(userObject.containsKey("username")) {
                          payload.put("sub", userObject.getString("username"));
                        } else {
                          payload.put("sub", userObject.getString("id"));
                        }
                        if(!userObject.isEmpty()) {
                          payload.put("user_id", userObject.getString("id"));
                        }
                        Future<String> fetchTokenFuture;
                        Object fetchTokenFlag = RestVerticle.MODULE_SPECIFIC_ARGS.get("fetch.token");
                        if(fetchTokenFlag != null && ((String)fetchTokenFlag).equals("no")) {
                        //if(true) {
                          fetchTokenFuture = Future.succeededFuture("dummytoken");
                        } else {
                          logger.debug("Fetching token from authz with payload " + payload.encode());
                          fetchTokenFuture = fetchToken(payload, tenantId, okapiURL, requestToken, vertxContext.owner());
                        }
                        fetchTokenFuture.setHandler(fetchTokenRes -> {
                          if(fetchTokenRes.failed()) {
                            String errMsg = "Error fetching token: " + fetchTokenRes.cause().getLocalizedMessage();
                            logger.error(errMsg);
                            asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainInternalServerError(getErrorResponse(errMsg))));
                          } else {
                              PostgresClient pgClient = PostgresClient.getInstance(vertxContext.owner(), tenantId);
                              // after succesfull login skip login attempts counter
                              getLoginAttemptsByUserId(userObject.getString("id"), pgClient, asyncResultHandler,
                                onLoginSuccessAttemptHandler(userObject, pgClient, asyncResultHandler));
                            //Append token as header to result
                            String authToken = fetchTokenRes.result();
                            asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withJsonCreated(authToken, entity)));
                          }
                        });
                      } else {
                        PostgresClient pgClient = PostgresClient.getInstance(vertxContext.owner(), tenantId);
                        OkapiConnectionParams params = new OkapiConnectionParams(okapiURL, tenantId, requestToken, vertxContext.owner(), null);

                        getLoginAttemptsByUserId(userObject.getString("id"), pgClient, asyncResultHandler,
                          onLoginFailAttemptHandler(userObject, params, pgClient, asyncResultHandler));
                        logger.error("Password does not match for userid " + userCred.getUserId());
                        asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainBadRequest("Bad credentials")));
                      }
                    }
                  } catch(Exception e) {
                    String message = e.getLocalizedMessage();
                    logger.error(message, e);
                    asyncResultHandler.handle(
                            Future.succeededFuture(PostAuthnLoginResponse.withPlainInternalServerError(message)));
                  }
                }
              });
              //Make sure this username isn't already added
            } catch(Exception e) {
              logger.error("Error with postgresclient on postAuthnLogin: " + e.getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainInternalServerError(INTERNAL_ERROR)));
            }
          }
        });
      });
    } catch(Exception e) {
      logger.debug("Error running on verticle for postAuthnLogin: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.withPlainInternalServerError(INTERNAL_ERROR)));
    }
  }

  @Override
  public void getAuthnCredentials(int length, int start, String sortBy, String query,
      Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler,
      Context vertxContext) throws Exception {
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
               asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.withPlainInternalServerError(INTERNAL_ERROR)));
             } else {
               CredentialsListObject credentialsListObject = new CredentialsListObject();
               List<Credential> credentialList = (List<Credential>)getReply.result().getResults();
               credentialsListObject.setCredentials(credentialList);
               credentialsListObject.setTotalRecords(getReply.result().getResultInfo().getTotalRecords());
               asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.withJsonOK(credentialsListObject)));
             }
           });
         } catch(Exception e) {
           logger.debug("Error invoking Postgresclient: "+ e.getLocalizedMessage());
           asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.withPlainInternalServerError(INTERNAL_ERROR)));
         }
       });
    } catch(Exception e) {
      logger.debug("Error running on vertx context: " + e.getLocalizedMessage());
      if(e.getCause() != null && e.getCause().getClass().getSimpleName().contains("CQLParseException")) {
        asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.withPlainBadRequest("CQL Parsing Error for '" + query + "': " +
                e.getLocalizedMessage())));
      } else {
        asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.withPlainInternalServerError(INTERNAL_ERROR)));
      }
    }
  }

  @Override
  public void postAuthnCredentials(LoginCredentialsJson entity,
      Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext)
      throws Exception {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        String okapiURL = okapiHeaders.get(OKAPI_URL_HEADER);
        String requestToken = okapiHeaders.get(OKAPI_TOKEN_HEADER);
        Future<JsonObject> userVerifyFuture;
        if(entity.getUserId() != null) {
          userVerifyFuture = Future.succeededFuture(new JsonObject().put("id",
              entity.getUserId()));
        } else {
          userVerifyFuture = lookupUser(entity.getUsername(), null,
            tenantId, okapiURL, requestToken, vertxContext.owner());
        }
        userVerifyFuture.setHandler(verifyRes -> {
          if(verifyRes.failed()) {
            String message = "Error looking up user: " + verifyRes.cause()
                .getLocalizedMessage();
            logger.error(message, verifyRes.cause());
            asyncResultHandler.handle(Future.succeededFuture(PostAuthnCredentialsResponse
                .withPlainBadRequest(message)));
          } else {
            JsonObject userOb = verifyRes.result();
            Criteria userIdCrit = new Criteria();
            userIdCrit.addField(CREDENTIAL_USERID_FIELD);
            userIdCrit.setOperation("=");
            userIdCrit.setValue(userOb.getString("id"));
            try {
              PostgresClient.getInstance(vertxContext.owner(), tenantId).get(
                  TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(userIdCrit),
                  true, getCredReply -> {
                  try {
                    if(getCredReply.failed()) {
                      String message = getCredReply.cause().getLocalizedMessage();
                      logger.error(message);
                      asyncResultHandler.handle(Future.succeededFuture(
                          PostAuthnCredentialsResponse.withPlainInternalServerError(message)));
                    } else {
                      List<Credential> credList = (List<Credential>) getCredReply
                          .result().getResults();
                      if (credList.size() > 0) {
                        String message = "There already exists credentials for user id '"
                            + userOb.getString("id") + "'";
                        logger.error(message);
                        asyncResultHandler.handle(Future.succeededFuture(
                            PostAuthnCredentialsResponse.withJsonUnprocessableEntity(
                            ValidationHelper.createValidationErrorMessage(
                            CREDENTIAL_USERID_FIELD, userOb.getString("id"), message))));
                      } else {
                        //Now we can create a new Credential
                        Credential credential = new Credential();
                        credential.setId(UUID.randomUUID().toString());
                        credential.setUserId(userOb.getString("id"));
                        credential.setSalt(authUtil.getSalt());
                        credential.setHash(authUtil.calculateHash(entity.getPassword(),
                            credential.getSalt()));
                        //And save it
                        PostgresClient pgClient = PostgresClient.getInstance(
                            vertxContext.owner(), tenantId);
                        pgClient.save(TABLE_NAME_CREDENTIALS, credential.getId(),
                            credential, saveReply -> {
                          if(saveReply.failed()) {
                            String message = "Saving record failed: "
                                + saveReply.cause().getLocalizedMessage();
                            logger.error(message, saveReply.cause());
                            asyncResultHandler.handle(Future.succeededFuture(
                                PostAuthnCredentialsResponse
                                .withPlainInternalServerError(message)));
                          } else {
                            asyncResultHandler.handle(Future.succeededFuture(
                                PostAuthnCredentialsResponse
                                .withJsonCreated(credential)));
                          }
                        });
                      }
                    }
                  } catch (Exception e) {
                    String message = e.getLocalizedMessage();
                    logger.error(message, e);
                    asyncResultHandler.handle(Future.succeededFuture(
                            PostAuthnCredentialsResponse.withPlainInternalServerError(message)));
                  }
                });
              } catch (Exception e) {
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
      asyncResultHandler.handle(Future.succeededFuture(PostAuthnCredentialsResponse.withPlainInternalServerError(INTERNAL_ERROR)));
    }
  }

  @Override
  public void putAuthnCredentialsById(String id, LoginCredentialsJson entity,
      Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler,
      Context vertxContext) throws Exception {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        Criteria idCrit = new Criteria();
        idCrit.addField(CREDENTIAL_ID_FIELD);
        idCrit.setOperation("=");
        idCrit.setValue(id);
        try {
          PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(idCrit),true, getReply -> {
            if(getReply.failed()) {
              logger.debug("PostgresClient get operation failed: " + getReply.cause().getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
            } else {
              List<Credential> credList = (List<Credential>)getReply.result().getResults();
              if(credList.isEmpty()) {
                asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByIdResponse.withPlainNotFound("No credentials found")));
              } else {
                Credential cred = credList.get(0);
                String newSalt = authUtil.getSalt();
                String newHash = authUtil.calculateHash(entity.getPassword(), newSalt);
                cred.setHash(newHash);
                cred.setSalt(newSalt);
                try {
                  PostgresClient.getInstance(vertxContext.owner(), tenantId).update(TABLE_NAME_CREDENTIALS, cred, new Criterion(idCrit), true, putReply -> {
                    if(putReply.failed()) {
                      logger.debug("Error with PostgresClient update operation: " + putReply.cause().getLocalizedMessage());
                      asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
                    } else {
                     asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByIdResponse.withJsonOK(entity)));
                    }
                  });
                } catch(Exception e) {
                  logger.debug("Error with PostgresClient: " + e.getLocalizedMessage());
                  asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
                }
              }
            }
          });
        } catch(Exception e) {
          logger.debug("Error with PostgresClient: " + e.getLocalizedMessage());
          asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
        }
      });
    } catch(Exception e) {
      logger.debug("Error running on vertx context: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
    }
  }
  @Override
  public void getAuthnCredentialsById(String id, Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler,
      Context vertxContext) throws Exception {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        try {
          testForFile(CREDENTIAL_SCHEMA_PATH);
          Criteria idCrit = new Criteria(CREDENTIAL_SCHEMA_PATH);
          idCrit.addField(CREDENTIAL_ID_FIELD);
          idCrit.setOperation("=");
          idCrit.setValue(id);
          PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(idCrit), true, false, getReply -> {
            if(getReply.failed()) {
              logger.debug("Error in PostgresClient get operation: " + getReply.cause().getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
            } else {
              List<Credential> credList = (List<Credential>)getReply.result().getResults();
              if(credList.isEmpty()) {
                asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.withPlainNotFound("No credentials for id " + id + " found")));
              } else {
                asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.withJsonOK(credList.get(0))));
              }
            }
          });
        } catch(Exception e) {
          logger.debug(POSTGRES_ERROR + e.getLocalizedMessage());
          asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
        }
      });
    } catch(Exception e) {
      logger.debug("Error running on vertx context: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
    }
  }


  @Override
  public void deleteAuthnCredentialsById(String id, Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext)
      throws Exception {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        Criteria nameCrit = new Criteria();
        nameCrit.addField(CREDENTIAL_ID_FIELD);
        nameCrit.setOperation("=");
        nameCrit.setValue(id);
        try {
          PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(nameCrit), true, getReply -> {
            if(getReply.failed()) {
              logger.debug("Error in PostgresClient get operation: " + getReply.cause().getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
            } else {
              List<Credential> credList = (List<Credential>)getReply.result().getResults();
              if(credList.isEmpty()) {
                asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByIdResponse.withPlainNotFound("No credentials for id " + id + " found")));
              } else {
                try {
                  PostgresClient.getInstance(vertxContext.owner(), tenantId).delete(TABLE_NAME_CREDENTIALS, new Criterion(nameCrit), deleteReply-> {
                    if(deleteReply.failed()) {
                      logger.debug("Error in PostgresClient get operation: " + deleteReply.cause().getLocalizedMessage());
                      asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
                    } else {
                      asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByIdResponse.withPlainNoContent("")));
                    }
                   });
                } catch(Exception e) {
                  logger.debug("Error from PostgresClient: " + e.getLocalizedMessage());
                  asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
                }
              }
            }
          });
        } catch(Exception e) {
          logger.debug(POSTGRES_ERROR + e.getLocalizedMessage());
          asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
        }
      });
    } catch(Exception e) {
      logger.debug("Error running on vertx context: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByIdResponse.withPlainInternalServerError(INTERNAL_ERROR)));
    }
  }

  private void testForFile(String path) {
    URL u = LoginAPI.class.getClassLoader().getResource(path);
    if(u == null) {
      throw(new MissingResourceException(path, LoginAPI.class.getName(), path));
    }
  }

  @Override
  public void postAuthnUpdate(UpdateCredentialsJson entity,
      Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler,
      Context vertxContext)
      throws Exception {
    vertxContext.runOnContext(v -> {
      try {
        Future<JsonObject> userVerifiedFuture;
        String tenantId = getTenant(okapiHeaders);
        String okapiURL = okapiHeaders.get(OKAPI_URL_HEADER);
        String requestToken = okapiHeaders.get(OKAPI_TOKEN_HEADER);
        if(requestToken == null) {
          logger.error("Missing request token");
          asyncResultHandler.handle(Future.succeededFuture(PostAuthnUpdateResponse
              .withPlainBadRequest("Missing Okapi token header")));
          return;
        }
        if(entity.getUserId() == null && entity.getUsername() == null) {
          logger.error("No username or userId provided for login attempt");
          asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
              .withPlainBadRequest("You must provide a username or userId")));
          return;
        }
        if(entity.getNewPassword() == null) {
          asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
              .withPlainBadRequest("You must provide a new password")));
          return;
        }
        if(entity.getUserId() != null && !requireActiveUser) {
          logger.debug("No need to look up user id");
          userVerifiedFuture = Future.succeededFuture(new JsonObject()
              .put("id", entity.getUserId()).put("active", true)
              .put("username", "__undefined__"));
        } else {
          logger.debug("Need to look up user id");
        if(entity.getUserId() != null) {
            userVerifiedFuture = lookupUser(null, entity.getUserId(), tenantId, okapiURL,
                requestToken, vertxContext.owner());
          } else {
            userVerifiedFuture = lookupUser(entity.getUsername(), null, tenantId, okapiURL,
                requestToken, vertxContext.owner());
          }
        }
        userVerifiedFuture.setHandler(verifyResult -> {
          if(verifyResult.failed()) {
            String errMsg = "Error verifying user existence: " + verifyResult
                .cause().getLocalizedMessage();
            logger.error(errMsg);
            asyncResultHandler.handle(Future.succeededFuture(
                PostAuthnUpdateResponse.withPlainBadRequest(getErrorResponse(
                errMsg))));
          } else {
            checkValidLogin(verifyResult.result().getString("id"), entity.getPassword(),
                tenantId, vertxContext).setHandler(checkLoginResult -> {
              if(checkLoginResult.failed()) {
                String message = checkLoginResult.cause().getLocalizedMessage();
                logger.error(message);
                asyncResultHandler.handle(Future.succeededFuture(
                    PostAuthnUpdateResponse.withPlainInternalServerError(message)));
              } else if(!checkLoginResult.result()) { //Failed login, 401
                asyncResultHandler.handle(Future.succeededFuture(
                    PostAuthnUpdateResponse.withPlainUnauthorized("Invalid credentials")));
              } else { //Password checks out, we can proceed
                checkValidPassword(entity.getNewPassword()).setHandler(
                    checkPasswordResult -> {
                  if(checkPasswordResult.failed()) {
                    String message = checkPasswordResult.cause().getLocalizedMessage();
                    logger.error(message);
                    asyncResultHandler.handle(Future.succeededFuture(
                        PostAuthnUpdateResponse.withPlainInternalServerError(message)));
                  } else if(checkPasswordResult.result() != null) { //Errors found
                    asyncResultHandler.handle(Future.succeededFuture(
                        PostAuthnUpdateResponse.withJsonUnprocessableEntity(
                        checkPasswordResult.result())));
                  } else { //Update the credentials with the new password
                    Credential newCred = makeCredentialObject(null, verifyResult.result().getString("id"),
                        entity.getNewPassword());
                    updateCredential(newCred, tenantId, vertxContext)
                        .setHandler(updateCredResult -> {
                      if(updateCredResult.failed()) {
                        String message = updateCredResult.cause().getLocalizedMessage();
                        logger.error(message);
                        asyncResultHandler.handle(Future.succeededFuture(
                            PostAuthnUpdateResponse.withPlainInternalServerError(message)));
                      } else {
                        if(!updateCredResult.result()) { //404
                          asyncResultHandler.handle(Future.succeededFuture(
                            PostAuthnUpdateResponse.withPlainBadRequest(
                            "Unable to update credentials for that userId")));
                        } else {
                           asyncResultHandler.handle(Future.succeededFuture(
                               PostAuthnUpdateResponse.withPlainNoContent(tenantId)));
                        }
                      }
                    });
                  }
                });
              }
            });
          }
        });
      } catch(Exception e) {
        String message = e.getLocalizedMessage();
        logger.error(message, e);
        asyncResultHandler.handle(Future.succeededFuture(PostAuthnUpdateResponse
            .withPlainInternalServerError(message)));
      }
    });
  }

  private Future<Boolean> checkValidLogin(String userId, String password,
      String tenantId, Context vertxContext) {
    Future<Boolean> validLoginFuture = Future.future();
    PostgresClient pgClient = PostgresClient.getInstance(vertxContext.owner(),
        tenantId);
    //Get credentials
    Criteria credCrit = new Criteria()
        .addField(CREDENTIAL_USERID_FIELD)
        .setOperation("=")
        .setValue(userId);
    pgClient.get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(credCrit),
        true, getReply -> {
      if(getReply.failed()) {
        validLoginFuture.fail(getReply.cause());
      } else {
        List<Credential> credList = (List<Credential>) getReply.result().getResults();
        if(credList.isEmpty()) {
          validLoginFuture.fail("No valid credential for that userId found");
        }
        Credential userCred = credList.get(0);
        String calculatedHash = authUtil.calculateHash(password, userCred.getSalt());
        if(calculatedHash.equals(userCred.getHash())) {
          validLoginFuture.complete(Boolean.TRUE);
        } else {
          validLoginFuture.complete(Boolean.FALSE);
        }
      }
    });
    return validLoginFuture;
  }

  private Future<Errors> checkValidPassword(String password) {
    //Here is where we make calls to whatever password validation we need to do
    return Future.succeededFuture(); //Null result
  }

  private Credential makeCredentialObject(String id, String userId, String password) {
    String salt = authUtil.getSalt();
    String hash = authUtil.calculateHash(password, salt);
    Credential cred = new Credential();
    cred.setId(id);
    cred.setUserId(userId);
    cred.setSalt(salt);
    cred.setHash(hash);
    return cred;
  }

  private Future<Boolean> updateCredential(Credential cred, String tenantId,
      Context vertxContext) {
    PostgresClient pgClient = PostgresClient.getInstance(vertxContext.owner(),
        tenantId);
    Future<Boolean> future = Future.future();
    if(cred.getId() == null && cred.getUserId() == null) {
      return Future.failedFuture("Need a userId or a credential id defined");
    }
    Future<Credential> credentialFuture;
    if(cred.getId() != null) {
      credentialFuture = Future.succeededFuture(cred);
    } else {
      credentialFuture = getCredentialByUserId(cred.getUserId(), tenantId,
          vertxContext);
    }
    credentialFuture.setHandler(credResult -> {
      if(credResult.failed()) { future.fail(credResult.cause()); return; }
      cred.setId(credResult.result().getId());
      pgClient.update(TABLE_NAME_CREDENTIALS, cred, cred.getId(), updateReply -> {
        if(updateReply.failed()) {
          future.fail(updateReply.cause());
          return;
        }
        if(updateReply.result().getUpdated() == 0) {
          future.complete(Boolean.FALSE);
        } else {
          future.complete(Boolean.TRUE);
        }
      });
    });
    return future;
  }

  private Future<Credential> getCredentialByUserId(String userId, String tenantId,
      Context vertxContext) {
    Future<Credential> future = Future.future();
    Criteria userIdCrit = new Criteria()
        .addField(CREDENTIAL_USERID_FIELD)
        .setOperation("=")
        .setValue(userId);
    PostgresClient pgClient = PostgresClient.getInstance(vertxContext.owner(),
        tenantId);
    pgClient.get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(userIdCrit),
        true, getReply -> {
      if(getReply.failed()) {
        future.fail(getReply.cause());
      } else {
        List<Credential> credList = (List<Credential>) getReply.result().getResults();
        if(credList.isEmpty()) {
          future.fail("No credential found with that userId");
        } else {
          future.complete(credList.get(0));
        }
      }
    });
    return future;
  }
}
