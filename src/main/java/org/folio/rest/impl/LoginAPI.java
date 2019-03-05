package org.folio.rest.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
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
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.ConfigResponse;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.CredentialsListObject;
import org.folio.rest.jaxrs.model.Error;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.LogEvent;
import org.folio.rest.jaxrs.model.LogEvents;
import org.folio.rest.jaxrs.model.LogResponse;
import org.folio.rest.jaxrs.model.LoginAttempts;
import org.folio.rest.jaxrs.model.LoginCredentials;
import org.folio.rest.jaxrs.model.Parameter;
import org.folio.rest.jaxrs.model.Password;
import org.folio.rest.jaxrs.model.PasswordCreate;
import org.folio.rest.jaxrs.model.PasswordReset;
import org.folio.rest.jaxrs.model.PasswordValid;
import org.folio.rest.jaxrs.model.ResponseCreateAction;
import org.folio.rest.jaxrs.model.ResponseResetAction;
import org.folio.rest.jaxrs.model.UpdateCredentials;
import org.folio.rest.jaxrs.resource.Authn;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Offset;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.tools.utils.TenantTool;
import org.folio.rest.tools.utils.ValidationHelper;
import org.folio.services.ConfigurationService;
import org.folio.services.LogStorageService;
import org.folio.services.PasswordStorageService;
import org.folio.util.AuthUtil;
import org.folio.util.LoginAttemptsHelper;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.UUID;

import static javax.ws.rs.core.HttpHeaders.ACCEPT;
import static javax.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.util.LoginAttemptsHelper.LOGIN_ATTEMPTS_SCHEMA_PATH;
import static org.folio.util.LoginAttemptsHelper.TABLE_NAME_LOGIN_ATTEMPTS;
import static org.folio.util.LoginAttemptsHelper.buildCriteriaForUserAttempts;
import static org.folio.util.LoginConfigUtils.EVENT_CONFIG_PROXY_CONFIG_ADDRESS;
import static org.folio.util.LoginConfigUtils.EVENT_CONFIG_PROXY_STORY_ADDRESS;
import static org.folio.util.LoginConfigUtils.PW_CONFIG_PROXY_STORY_ADDRESS;
import static org.folio.util.LoginConfigUtils.VALUE_IS_NOT_FOUND;
import static org.folio.util.LoginConfigUtils.createFutureResponse;
import static org.folio.util.LoginConfigUtils.getResponseEntity;

/**
 * @author kurt
 */
public class LoginAPI implements Authn {

  private static final String TABLE_NAME_CREDENTIALS = "auth_credentials";
  public static final String OKAPI_TENANT_HEADER = "x-okapi-tenant";
  public static final String OKAPI_TOKEN_HEADER = "x-okapi-token";
  public static final String OKAPI_URL_HEADER = "x-okapi-url";
  public static final String OKAPI_USER_ID_HEADER = "x-okapi-user-id";
  public static final String OKAPI_REQUEST_TIMESTAMP_HEADER = "x-okapi-request-timestamp";
  public static final String OKAPI_REQUEST_IP_HEADER = "x-okapi-request-ip";
  public static final String X_FORWARDED_FOR_HEADER = "X-Forwarded-For";
  private static final String CREDENTIAL_USERID_FIELD = "'userId'";
  private static final String CREDENTIAL_ID_FIELD = "'id'";
  private static final String ERROR_RUNNING_VERTICLE = "Error running on verticle for `%s`: %s";
  private static final String ERROR_PW_ACTION_ENTITY_NOT_FOUND = "Password action with ID: `%s` was not found in the db";
  private static final String CREDENTIAL_SCHEMA_PATH = "ramls/credentials.json";
  private static final String APPLICATION_JSON_CONTENT_TYPE = "application/json";
  private static final String POSTGRES_ERROR = "Error from PostgresClient: ";
  private static final String VERTX_CONTEXT_ERROR = "Error running on vertx context: ";
  private static final String POSTGRES_ERROR_GET = "Error in PostgresClient get operation: ";
  private static final String INTERNAL_ERROR = "Internal Server error";
  private static final String CODE_USERNAME_INCORRECT = "username.incorrect";
  public static final String CODE_CREDENTIAL_PW_INCORRECT = "password.incorrect";
  /* show warning message user has 5 (current value) failed attempts and block user */
  public static final String CODE_FIFTH_FAILED_ATTEMPT_BLOCKED = "password.incorrect.block.user";
  private static final String CODE_USER_BLOCKED = "user.blocked";
  /* show warning message user has 3 (current value) failed attempts */
  public static final String CODE_THIRD_FAILED_ATTEMPT = "password.incorrect.warn.user";
  public static final String USERNAME = "username";
  private static final String TYPE_ERROR = "error";
  private static final String ACTIVE = "active";
  public static final String MESSAGE_LOG_CONFIGURATION_IS_DISABLED = "Logging settings are disabled";
  public static final String MESSAGE_LOG_EVENT_IS_DISABLED = "For event logging `%s` is disabled";
  private static final String ERROR_EVENT_CONFIG_NOT_FOUND = "Event Config with `%s`: `%s` was not found in the db";
  private AuthUtil authUtil = new AuthUtil();
  private boolean suppressErrorResponse = false;
  private boolean requireActiveUser = Boolean.parseBoolean(MODULE_SPECIFIC_ARGS
      .getOrDefault("require.active", "true"));
  private int lookupTimeout = Integer.parseInt(MODULE_SPECIFIC_ARGS
      .getOrDefault("lookup.timeout", "1000"));

  private final Logger logger = LoggerFactory.getLogger(LoginAPI.class);

  private String vTenantId;
  private LogStorageService logStorageService;
  private ConfigurationService configurationService;
  private PasswordStorageService passwordStorageService;
  private LoginAttemptsHelper loginAttemptsHelper;

  public LoginAPI(Vertx vertx, String tenantId) {
    this.vTenantId = tenantId;
    initService(vertx);
  }

  private void initService(Vertx vertx) {
    passwordStorageService = PasswordStorageService.createProxy(vertx, PW_CONFIG_PROXY_STORY_ADDRESS);
    logStorageService = LogStorageService.createProxy(vertx, EVENT_CONFIG_PROXY_STORY_ADDRESS);
    configurationService = ConfigurationService.createProxy(vertx, EVENT_CONFIG_PROXY_CONFIG_ADDRESS);
    loginAttemptsHelper = new LoginAttemptsHelper(vertx);
  }

  private String getErrorResponse(String response) {
    if (suppressErrorResponse) {
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
  }

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
    String requestURL;
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
              .putHeader(CONTENT_TYPE, APPLICATION_JSON_CONTENT_TYPE)
              .putHeader(ACCEPT, APPLICATION_JSON_CONTENT_TYPE);
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
      request.exceptionHandler(future::fail);
      request.end();
    } catch(Exception e) {
      String message = "User lookup failed at url '" + requestURL + "': " + e.getLocalizedMessage();
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

    request.putHeader(OKAPI_TENANT_HEADER, tenant)
      .putHeader(OKAPI_TOKEN_HEADER, requestToken)
      .putHeader(CONTENT_TYPE, APPLICATION_JSON_CONTENT_TYPE)
      .putHeader(ACCEPT, APPLICATION_JSON_CONTENT_TYPE);

    request.handler(response -> response.bodyHandler(buf -> {
      try {
        String token = null;
        if(response.statusCode() == 200 || response.statusCode() == 201) {
          if(response.statusCode() == 200) {
            token = response.getHeader(OKAPI_TOKEN_HEADER);
          } else if(response.statusCode() == 201) {
            JsonObject json = new JsonObject(buf.toString());
            token = json.getString("token");
          }
          if(token == null) {
            future.fail(String.format("Got response %s fetching token, but content is null",
                response.statusCode()));
          } else {
            logger.debug("Got token " + token + " from authz");
            future.complete(token);
          }
        } else {
          future.fail("Got response " + response.statusCode() + " fetching token");
        }
      } catch(Exception e) {
        future.fail(String.format("Error getting token: %s", e.getLocalizedMessage()));
      }
    }));
    request.exceptionHandler(future::fail);
    request.end(new JsonObject().put("payload", payload).encode());
    return future;
  }

  private Future<String> fetchRefreshToken(String userId, String sub, String tenant,
      String okapiURL, String requestToken, Vertx vertx) {
    Future<String> future = Future.future();
    HttpClient client = vertx.createHttpClient();
    HttpClientRequest request = client.postAbs(okapiURL + "/refreshtoken");
    request.putHeader(OKAPI_TENANT_HEADER, tenant)
      .putHeader(OKAPI_TOKEN_HEADER, requestToken)
      .putHeader(CONTENT_TYPE, APPLICATION_JSON_CONTENT_TYPE)
      .putHeader(ACCEPT, APPLICATION_JSON_CONTENT_TYPE);
    request.handler(response -> {
    });
    JsonObject payload = new JsonObject().put("userId", userId).put("sub", sub);
    request.handler(response -> response.bodyHandler(buf -> {
      if(response.statusCode() != 201) {
        String message = String.format("Expected code 201 from /refreshtoken, got %s",
            response.statusCode());
        future.fail(message);
      } else {
        String refreshToken;
        try {
          refreshToken = new JsonObject(buf.toString()).getString("refreshToken");
        } catch(Exception e) {
          future.fail(e);
          return;
        }
        future.complete(refreshToken);
      }
    }));
    request.exceptionHandler(future::fail);
    request.end(payload.encode());
    return future;
  }

  @Override
  public void getAuthnLoginAttemptsById(String id, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        try {
          testForFile(LOGIN_ATTEMPTS_SCHEMA_PATH);
          PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_LOGIN_ATTEMPTS, LoginAttempts.class, buildCriteriaForUserAttempts(id), true,  getReply -> {
            if(getReply.failed()) {
              logger.debug(POSTGRES_ERROR_GET + getReply.cause().getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
            } else {
              List<LoginAttempts> attemptsList = getReply.result().getResults();
              if(attemptsList.isEmpty()) {
                asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.respond404WithTextPlain("No user login attempts for id " + id + " found")));
              } else {
                asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.respond200WithApplicationJson(attemptsList.get(0))));
              }
            }
          });
        } catch(Exception e) {
          logger.debug(POSTGRES_ERROR + e.getLocalizedMessage());
          asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
        }
      });
    } catch(Exception e) {
      logger.debug(VERTX_CONTEXT_ERROR + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
    }
  }

  @Override
  public void postAuthnLogin(String userAgent, String xForwardedFor,//NOSONAR
                             LoginCredentials entity, Map<String, String> okapiHeaders,
                             Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    try {
      testForFile(CREDENTIAL_SCHEMA_PATH);
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        String okapiURL = okapiHeaders.get(OKAPI_URL_HEADER);
        String requestToken = okapiHeaders.get(OKAPI_TOKEN_HEADER);
        if(requestToken == null) {
          logger.error("Missing request token");
          asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
              .respond400WithTextPlain("Missing Okapi token header")));
          return;
        }
        Future<JsonObject> userVerified;
        if(entity.getUserId() == null && entity.getUsername() == null) {
          logger.error("No username or userId provided for login attempt");
          asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
              .respond400WithTextPlain("You must provide a username or userId")));
          return;
        }
        if(entity.getPassword() == null) {
          logger.error("No password provided for login attempt");
          asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
              .respond400WithTextPlain("You must provide a password")));
          return;
        }
        if(entity.getUserId() != null && !requireActiveUser) {
          logger.debug("No need to look up user id");
          userVerified = Future.succeededFuture(new JsonObject()
              .put("id", entity.getUserId()).put(ACTIVE, true)
              .put(USERNAME, "__undefined__"));
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
                PostAuthnLoginResponse.respond422WithApplicationJson(
                  getErrors(errMsg, CODE_USERNAME_INCORRECT, new ImmutablePair<>(USERNAME, entity.getUsername())))
            ));

          } else {
            //User's okay, let's try to login
            try {
              JsonObject userObject = verifyResult.result();
              if(!userObject.containsKey("id")) {
                logger.error("No 'id' key in returned user object");
                asyncResultHandler.handle(Future.succeededFuture(
                    PostAuthnLoginResponse.respond500WithTextPlain(
                    "No user id could be found")));
                return;
              }
              if(requireActiveUser) {
                boolean foundActive = false;
                if(userObject.containsKey(ACTIVE) && userObject.getBoolean(ACTIVE)) {
                  foundActive = true;
                }
                if(!foundActive) {
                  logger.error("User could not be verified as active");
                  asyncResultHandler.handle(Future.succeededFuture(
                    PostAuthnLoginResponse.respond422WithApplicationJson(
                    getErrors("User must be flagged as active", CODE_USER_BLOCKED))
                  ));
                  return;
                }
              }
              Criteria useridCrit = new Criteria(CREDENTIAL_SCHEMA_PATH);
              useridCrit.addField(CREDENTIAL_USERID_FIELD);
              useridCrit.setOperation(Criteria.OP_EQUAL);
              useridCrit.setValue(userObject.getString("id"));
              PostgresClient.getInstance(vertxContext.owner(), tenantId).get(
                  TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(useridCrit),
                  true, getReply-> {
                if(getReply.failed()) {
                  logger.error("Error in postgres get operation: " +
                      getReply.cause().getLocalizedMessage());
                  asyncResultHandler.handle(Future.succeededFuture(
                      PostAuthnLoginResponse.respond500WithTextPlain(
                      INTERNAL_ERROR)));
                } else {
                  try {
                    List<Credential> credList = getReply.result().getResults();
                    if(credList.isEmpty()) {
                      logger.error("No matching credentials found for userid " + userObject.getString("id"));
                      asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.respond400WithTextPlain("No credentials match that login")));
                    } else {
                      Credential userCred = credList.get(0);
                      if(userCred.getHash() == null || userCred.getSalt() == null) {
                        String message = "Error retrieving stored hash and salt from credentials";
                        logger.error(message);
                        asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.respond500WithTextPlain(message)));
                        return;
                      }
                      logger.debug("Testing hash for credentials for user with id '" + userObject.getString("id") + "'");
                      String testHash = authUtil.calculateHash(entity.getPassword(), userCred.getSalt());
                      String sub;
                      if(userCred.getHash().equals(testHash)) {
                        JsonObject payload = new JsonObject();
                        if(userObject.containsKey(USERNAME)) {
                          sub = userObject.getString(USERNAME);
                        } else {
                          sub = userObject.getString("id");
                        }
                        payload.put("sub", sub);
                        if(!userObject.isEmpty()) {
                          payload.put("user_id", userObject.getString("id"));
                        }
                        Future<String> fetchTokenFuture;
                        Future<String> fetchRefreshTokenFuture;
                        String fetchTokenFlag = RestVerticle.MODULE_SPECIFIC_ARGS.get("fetch.token");
                        if(fetchTokenFlag != null && fetchTokenFlag.equals("no")) {
                          fetchTokenFuture = Future.succeededFuture("dummytoken");
                        } else {
                          logger.debug("Fetching token from authz with payload " + payload.encode());
                          fetchTokenFuture = fetchToken(payload, tenantId, okapiURL, requestToken, vertxContext.owner());
                        }
                        fetchRefreshTokenFuture = fetchRefreshToken(userObject.getString("id"),
                            sub, tenantId, okapiURL, requestToken, vertxContext.owner());
                        CompositeFuture compositeFuture = CompositeFuture.join(fetchTokenFuture,
                            fetchRefreshTokenFuture);

                        compositeFuture.setHandler(fetchTokenRes -> {
                          if(fetchTokenFuture.failed()) {
                            String errMsg = "Error fetching token: " + fetchTokenFuture.cause().getLocalizedMessage();
                            logger.error(errMsg);
                            asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.respond500WithTextPlain(getErrorResponse(errMsg))));
                          } else {
                            String refreshToken = null;
                            if(fetchRefreshTokenFuture.failed()) {
                              logger.error(String.format("Error getting refresh token: %s",
                                  fetchRefreshTokenFuture.cause().getLocalizedMessage()));
                            } else {
                              refreshToken = fetchRefreshTokenFuture.result();
                            }
                            PostgresClient pgClient = PostgresClient.getInstance(vertxContext.owner(), tenantId);
                              // after succesfull login skip login attempts counter
                            String finalRefreshToken = refreshToken;
                            Map<String, String> requestHeaders = new HashMap<>(okapiHeaders);
                            requestHeaders.put(HttpHeaders.USER_AGENT, userAgent);
                            requestHeaders.put(X_FORWARDED_FOR_HEADER, xForwardedFor);
                            loginAttemptsHelper.getLoginAttemptsByUserId(userObject.getString("id"), pgClient)
                              .compose(attempts ->
                                loginAttemptsHelper.onLoginSuccessAttemptHandler(userObject, requestHeaders, attempts))
                              .setHandler(reply -> {
                                if (reply.failed()) {
                                  asyncResultHandler.handle(Future.succeededFuture(
                                    PostAuthnLoginResponse.respond500WithTextPlain(INTERNAL_ERROR)));
                                } else {
                                  //Append token as header to result
                                  String authToken = fetchTokenFuture.result();
                                  asyncResultHandler.handle(Future.succeededFuture(
                                    PostAuthnLoginResponse.respond201WithApplicationJson(entity,
                                      PostAuthnLoginResponse.headersFor201().withXOkapiToken(authToken)
                                        .withRefreshtoken(finalRefreshToken))));
                                }
                              });

                          }
                        });
                      } else {
                        PostgresClient pgClient = PostgresClient.getInstance(vertxContext.owner(), tenantId);
                        Map<String, String> requestHeaders = new HashMap<>(okapiHeaders);
                        requestHeaders.put(HttpHeaders.USER_AGENT, userAgent);
                        requestHeaders.put(X_FORWARDED_FOR_HEADER, xForwardedFor);
                        loginAttemptsHelper.getLoginAttemptsByUserId(userObject.getString("id"), pgClient)
                          .compose(attempts ->
                            loginAttemptsHelper.onLoginFailAttemptHandler(userObject, requestHeaders, attempts))
                          .setHandler(errors -> {
                            if (errors.failed()) {
                              asyncResultHandler.handle(Future.succeededFuture(
                                Authn.PostAuthnLoginResponse.respond500WithTextPlain(INTERNAL_ERROR)));
                            } else {
                              logger.error("Password does not match for userid " + userCred.getUserId());
                              asyncResultHandler.handle(Future.succeededFuture(
                                Authn.PostAuthnLoginResponse.respond422WithApplicationJson(errors.result())));
                            }
                          });
                      }
                    }
                  } catch(Exception e) {
                    String message = e.getLocalizedMessage();
                    logger.error(message, e);
                    asyncResultHandler.handle(
                            Future.succeededFuture(PostAuthnLoginResponse.respond500WithTextPlain(message)));
                  }
                }
              });
              //Make sure this username isn't already added
            } catch(Exception e) {
              logger.error("Error with postgresclient on postAuthnLogin: " + e.getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.respond500WithTextPlain(INTERNAL_ERROR)));
            }
          }
        });
      });
    } catch(Exception e) {
      logger.debug("Error running on verticle for postAuthnLogin: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.respond500WithTextPlain(INTERNAL_ERROR)));
    }
  }

  @Override
  public void getAuthnCredentials(int length, int start, String sortBy, String query,
      Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler,
      Context vertxContext) {
    try {
       vertxContext.runOnContext(v -> {
         String tenantId = getTenant(okapiHeaders);
         String[] fieldList = {"*"};
         try {
           CQLWrapper cql = getCQL(query, length, start - 1);
           PostgresClient.getInstance(vertxContext.owner(), tenantId).get(
                   TABLE_NAME_CREDENTIALS, Credential.class, fieldList, cql, true, false, getReply -> {
             if(getReply.failed()) {
               logger.debug(POSTGRES_ERROR_GET + getReply.cause().getLocalizedMessage());
               asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.respond500WithTextPlain(INTERNAL_ERROR)));
             } else {
               CredentialsListObject credentialsListObject = new CredentialsListObject();
               List<Credential> credentialList = getReply.result().getResults();
               credentialsListObject.setCredentials(credentialList);
               credentialsListObject.setTotalRecords(getReply.result().getResultInfo().getTotalRecords());
               asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.respond200WithApplicationJson(credentialsListObject)));
             }
           });
         } catch(Exception e) {
           logger.debug("Error invoking Postgresclient: "+ e.getLocalizedMessage());
           asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.respond500WithTextPlain(INTERNAL_ERROR)));
         }
       });
    } catch(Exception e) {
      logger.debug(VERTX_CONTEXT_ERROR + e.getLocalizedMessage());
      if(e.getCause() != null && e.getCause().getClass().getSimpleName().contains("CQLParseException")) {
        asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.respond400WithTextPlain("CQL Parsing Error for '" + query + "': " +
                e.getLocalizedMessage())));
      } else {
        asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsResponse.respond500WithTextPlain(INTERNAL_ERROR)));
      }
    }
  }

  @Override
  public void postAuthnCredentials(LoginCredentials entity,
      Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
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
                .respond400WithTextPlain(message)));
          } else {
            JsonObject userOb = verifyRes.result();
            Criteria userIdCrit = new Criteria();
            userIdCrit.addField(CREDENTIAL_USERID_FIELD);
            userIdCrit.setOperation(Criteria.OP_EQUAL);
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
                          PostAuthnCredentialsResponse.respond500WithTextPlain(message)));
                    } else {
                      List<Credential> credList = getCredReply.result().getResults();
                      if (!credList.isEmpty()) {
                        String message = "There already exists credentials for user id '"
                            + userOb.getString("id") + "'";
                        logger.error(message);
                        asyncResultHandler.handle(Future.succeededFuture(
                            PostAuthnCredentialsResponse.respond422WithApplicationJson(
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
                                .respond500WithTextPlain(message)));
                          } else {
                            asyncResultHandler.handle(Future.succeededFuture(
                                PostAuthnCredentialsResponse
                                  .respond201WithApplicationJson(credential)));
                          }
                        });
                      }
                    }
                  } catch (Exception e) {
                    String message = e.getLocalizedMessage();
                    logger.error(message, e);
                    asyncResultHandler.handle(Future.succeededFuture(
                            PostAuthnCredentialsResponse.respond500WithTextPlain(message)));
                  }
                });
              } catch (Exception e) {
                String message = e.getLocalizedMessage();
                logger.error(message, e);
                asyncResultHandler.handle(Future.succeededFuture(
                        PostAuthnCredentialsResponse.respond500WithTextPlain(message)));
            }
          }
        });
      });
    } catch(Exception e) {
      logger.error(VERTX_CONTEXT_ERROR + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(PostAuthnCredentialsResponse.respond500WithTextPlain(INTERNAL_ERROR)));
    }
  }

  @Override
  public void putAuthnCredentialsById(String id, LoginCredentials entity,
      Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler,
      Context vertxContext) {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        Criteria idCrit = new Criteria();
        idCrit.addField(CREDENTIAL_ID_FIELD);
        idCrit.setOperation(Criteria.OP_EQUAL);
        idCrit.setValue(id);
        try {
          PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(idCrit),true, getReply -> {
            if(getReply.failed()) {
              logger.debug("PostgresClient get operation failed: " + getReply.cause().getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
            } else {
              List<Credential> credList = getReply.result().getResults();
              if(credList.isEmpty()) {
                asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByIdResponse.respond404WithTextPlain("No credentials found")));
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
                      asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
                    } else {
                     asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByIdResponse.respond200WithApplicationJson(entity)));
                    }
                  });
                } catch(Exception e) {
                  logger.debug("Error with PostgresClient: " + e.getLocalizedMessage());
                  asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
                }
              }
            }
          });
        } catch(Exception e) {
          logger.debug("Error with PostgresClient: " + e.getLocalizedMessage());
          asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
        }
      });
    } catch(Exception e) {
      logger.debug(VERTX_CONTEXT_ERROR + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(PutAuthnCredentialsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
    }
  }

  @Override
  public void getAuthnCredentialsById(String id, Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler,
      Context vertxContext) {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        try {
          testForFile(CREDENTIAL_SCHEMA_PATH);
          Criteria idCrit = new Criteria(CREDENTIAL_SCHEMA_PATH);
          idCrit.addField(CREDENTIAL_ID_FIELD);
          idCrit.setOperation(Criteria.OP_EQUAL);
          idCrit.setValue(id);
          PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(idCrit), true, false, getReply -> {
            if(getReply.failed()) {
              logger.debug(POSTGRES_ERROR_GET + getReply.cause().getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
            } else {
              List<Credential> credList = getReply.result().getResults();
              if(credList.isEmpty()) {
                asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.respond404WithTextPlain("No credentials for id " + id + " found")));
              } else {
                asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.respond200WithApplicationJson(credList.get(0))));
              }
            }
          });
        } catch(Exception e) {
          logger.debug(POSTGRES_ERROR + e.getLocalizedMessage());
          asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
        }
      });
    } catch(Exception e) {
      logger.debug(VERTX_CONTEXT_ERROR + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(GetAuthnCredentialsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
    }
  }


  @Override
  public void deleteAuthnCredentialsById(String id, Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    try {
      vertxContext.runOnContext(v -> {
        String tenantId = getTenant(okapiHeaders);
        Criteria nameCrit = new Criteria();
        nameCrit.addField(CREDENTIAL_ID_FIELD);
        nameCrit.setOperation(Criteria.OP_EQUAL);
        nameCrit.setValue(id);
        try {
          PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(nameCrit), true, getReply -> {
            if(getReply.failed()) {
              logger.debug(POSTGRES_ERROR_GET + getReply.cause().getLocalizedMessage());
              asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
            } else {
              List<Credential> credList = getReply.result().getResults();
              if(credList.isEmpty()) {
                asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByIdResponse.respond404WithTextPlain("No credentials for id " + id + " found")));
              } else {
                try {
                  PostgresClient.getInstance(vertxContext.owner(), tenantId).delete(TABLE_NAME_CREDENTIALS, new Criterion(nameCrit), deleteReply-> {
                    if(deleteReply.failed()) {
                      logger.debug(POSTGRES_ERROR_GET + deleteReply.cause().getLocalizedMessage());
                      asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
                    } else {
                      asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByIdResponse.respond204WithTextPlain("")));
                    }
                   });
                } catch(Exception e) {
                  logger.debug(POSTGRES_ERROR + e.getLocalizedMessage());
                  asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
                }
              }
            }
          });
        } catch(Exception e) {
          logger.debug(POSTGRES_ERROR + e.getLocalizedMessage());
          asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
        }
      });
    } catch(Exception e) {
      logger.debug(VERTX_CONTEXT_ERROR + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
    }
  }

  /**
   * This method is /authn/password/repeatable endpoint implementation
   * which is used by programmatic rule of mod-password-validator.
   * It takes a password as a parameter and checks if the user used this password before.
   * If password was used before the response is 'valid' otherwise the response is 'invalid'.
   *
   * @param password password to be validated
   * @param okapiHeaders request headers
   * @param asyncResultHandler An AsyncResult<Response> Handler  {@link Handler}
   * @param vertxContext The Vertx Context Object <code>io.vertx.core.Context</code>
   */
  @Override
  public void postAuthnPasswordRepeatable(Password password, Map<String, String> okapiHeaders,
                                          Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    try {
      vertxContext.runOnContext(v ->
        passwordStorageService.isPasswordPreviouslyUsed(JsonObject.mapFrom(password), okapiHeaders, used -> {
          if (used.failed()) {
            asyncResultHandler.handle(
              Future.succeededFuture(PostAuthnPasswordRepeatableResponse.respond500WithTextPlain(INTERNAL_ERROR)));
            return;
          }

          if (used.result()) {
            asyncResultHandler.handle(Future.succeededFuture(PostAuthnPasswordRepeatableResponse.
              respond200WithApplicationJson(new PasswordValid().withResult("invalid"))));
          } else {
            asyncResultHandler.handle(Future.succeededFuture(PostAuthnPasswordRepeatableResponse.
              respond200WithApplicationJson(new PasswordValid().withResult("valid"))));
          }
        }));
    } catch(Exception e) {
      logger.debug(VERTX_CONTEXT_ERROR + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(
        PostAuthnPasswordRepeatableResponse.respond500WithTextPlain(INTERNAL_ERROR)));
    }
  }

  @Override
  public void postAuthnResetPassword(String userAgent, String xForwardedFor,
                                     PasswordReset entity, Map<String, String> okapiHeaders,
                                     Handler<AsyncResult<Response>> asyncHandler, Context context) {
    try {
      JsonObject passwordResetJson = JsonObject.mapFrom(entity);
      Map<String, String> requestHeaders = new HashMap<>(okapiHeaders);
      requestHeaders.put(HttpHeaders.USER_AGENT, userAgent);
      requestHeaders.put(X_FORWARDED_FOR_HEADER, xForwardedFor);
      context.runOnContext(contextHandler ->
        passwordStorageService.resetPassword(requestHeaders, passwordResetJson,
          serviceHandler -> {
            if (serviceHandler.failed()) {
              String errorMessage = serviceHandler.cause().getMessage();
              asyncHandler.handle(createFutureResponse(
                PostAuthnResetPasswordResponse.respond500WithTextPlain(errorMessage)));
              return;
            }
            JsonObject jsonObject = serviceHandler.result();
            Boolean isNotFound = Optional.ofNullable(jsonObject.getBoolean(VALUE_IS_NOT_FOUND)).orElse(false);
            if (isNotFound) {
              String actionId = entity.getPasswordResetActionId();
              String message = String.format(ERROR_PW_ACTION_ENTITY_NOT_FOUND, actionId);
              logger.debug(message);
              asyncHandler.handle(createFutureResponse(
                PostAuthnResetPasswordResponse.respond400WithTextPlain(message)));
              return;
            }

            ResponseResetAction response = getResponseEntity(serviceHandler, ResponseResetAction.class);
            asyncHandler.handle(createFutureResponse(
              PostAuthnResetPasswordResponse.respond201WithApplicationJson(response)));
          }));
    } catch (Exception ex) {
      String message = ex.getMessage();
      String errorMessage = String.format(ERROR_RUNNING_VERTICLE, "postAuthnResetPassword", message);
      logger.error(errorMessage, ex);
      asyncHandler.handle(createFutureResponse(
        PostAuthnResetPasswordResponse.respond500WithTextPlain(errorMessage)));
    }
  }

  @Override
  public void postAuthnPasswordResetAction(PasswordCreate entity, Map<String, String> okapiHeaders,
                                           Handler<AsyncResult<Response>> asyncHandler, Context context) {
    try {
      JsonObject passwordCreateJson = JsonObject.mapFrom(entity);
      context.runOnContext(contextHandler ->
        passwordStorageService.savePasswordAction(vTenantId, passwordCreateJson,
          serviceHandler -> {
            if (serviceHandler.failed()) {
              String errorMessage = serviceHandler.cause().getMessage();
              asyncHandler.handle(createFutureResponse(
                PostAuthnPasswordResetActionResponse.respond400WithTextPlain(errorMessage)));
              return;
            }

            ResponseCreateAction response = getResponseEntity(serviceHandler, ResponseCreateAction.class);
            asyncHandler.handle(createFutureResponse(
              PostAuthnPasswordResetActionResponse.respond201WithApplicationJson(response)));
          }));
    } catch (Exception ex) {
      String message = ex.getMessage();
      String errorMessage = String.format(ERROR_RUNNING_VERTICLE, "postAuthnPasswordResetAction", message);
      logger.error(errorMessage, ex);
      asyncHandler.handle(createFutureResponse(
        PostAuthnPasswordResetActionResponse.respond500WithTextPlain(errorMessage)));
    }
  }

  @Override
  public void getAuthnPasswordResetActionByActionId(String actionId, Map<String, String> okapiHeaders,
                                                    Handler<AsyncResult<Response>> asyncHandler, Context context) {
    try {
      context.runOnContext(contextHandler ->
        passwordStorageService.findPasswordActionById(vTenantId, actionId,
          serviceHandler -> {
            if (serviceHandler.failed()) {
              String errorMessage = serviceHandler.cause().getMessage();
              asyncHandler.handle(createFutureResponse(
                GetAuthnPasswordResetActionByActionIdResponse.respond400WithTextPlain(errorMessage)));
              return;
            }
            JsonObject jsonObject = serviceHandler.result();
            Boolean isNotFound = Optional.ofNullable(jsonObject.getBoolean(VALUE_IS_NOT_FOUND)).orElse(false);
            if (isNotFound) {
              String message = String.format(ERROR_PW_ACTION_ENTITY_NOT_FOUND, actionId);
              logger.debug(message);
              asyncHandler.handle(createFutureResponse(
                GetAuthnPasswordResetActionByActionIdResponse.respond404WithTextPlain(message)));
              return;
            }

            PasswordCreate response = getResponseEntity(serviceHandler, PasswordCreate.class);
            asyncHandler.handle(createFutureResponse(
              GetAuthnPasswordResetActionByActionIdResponse.respond200WithApplicationJson(response)));
          }));
    } catch (Exception ex) {
      String message = ex.getMessage();
      String errorMessage = String.format(ERROR_RUNNING_VERTICLE, "getAuthnPasswordResetActionByActionId", message);
      logger.error(errorMessage, ex);
      asyncHandler.handle(createFutureResponse(
        GetAuthnPasswordResetActionByActionIdResponse.respond500WithTextPlain(errorMessage)));
    }
  }

  @Override
  public void getAuthnLogEvents(int limit, int offset, String query,
                                Map<String, String> requestHeaders,
                                Handler<AsyncResult<Response>> asyncHandler, Context context) {
    try {
      JsonObject headers = JsonObject.mapFrom(requestHeaders);
      context.runOnContext(contextHandler ->
        configurationService.getEnableConfigurations(vTenantId, headers, serviceHandler -> {
          if (serviceHandler.failed()) {
            String errorMessage = serviceHandler.cause().getMessage();
            asyncHandler.handle(createFutureResponse(
              GetAuthnLogEventsResponse.respond500WithTextPlain(errorMessage)));
            return;
          }
          ConfigResponse responseEntity = getResponseEntity(serviceHandler, ConfigResponse.class);
          if (!responseEntity.getEnabled()) {
            asyncHandler.handle(createFutureResponse(
              GetAuthnLogEventsResponse.respond204WithTextPlain(MESSAGE_LOG_CONFIGURATION_IS_DISABLED)));
            return;
          }

          logStorageService.findAllEvents(vTenantId, limit, offset, query,
            storageHandler -> {
              if (storageHandler.failed()) {
                String errorMessage = storageHandler.cause().getMessage();
                asyncHandler.handle(createFutureResponse(
                  GetAuthnLogEventsResponse.respond500WithTextPlain(errorMessage)));
                return;
              }
              LogEvents response = getResponseEntity(storageHandler, LogEvents.class);
              asyncHandler.handle(createFutureResponse(
                GetAuthnLogEventsResponse.respond200WithApplicationJson(response)));
            });
        })
      );
    } catch (Exception ex) {
      String errorMessage = String.format(ERROR_RUNNING_VERTICLE, "getAuthnLogEvents", ex.getMessage());
      logger.error(errorMessage, ex);
      asyncHandler.handle(createFutureResponse(GetAuthnLogEventsResponse.respond500WithTextPlain(errorMessage)));
    }
  }

  @Override
  public void postAuthnLogEvents(LogEvent logEvent, Map<String, String> requestHeaders,
                                 Handler<AsyncResult<Response>> asyncHandler, Context context) {
    try {
      JsonObject headers = JsonObject.mapFrom(requestHeaders);
      context.runOnContext(contextHandler ->
        configurationService.getEnableConfigurations(vTenantId, headers, serviceHandler -> {
          if (serviceHandler.failed()) {
            String errorMessage = serviceHandler.cause().getMessage();
            asyncHandler.handle(createFutureResponse(
              PostAuthnLogEventsResponse.respond500WithTextPlain(errorMessage)));
            return;
          }
          ConfigResponse responseEntity = getResponseEntity(serviceHandler, ConfigResponse.class);
          if (!responseEntity.getEnabled()) {
            asyncHandler.handle(createFutureResponse(
              PostAuthnLogEventsResponse.respond204WithTextPlain(MESSAGE_LOG_CONFIGURATION_IS_DISABLED)));
            return;
          }
          List<String> enableConfigCodes = responseEntity.getConfigs();
          String eventCode = logEvent.getEventType().toString();
          if (!enableConfigCodes.contains(eventCode)) {
            asyncHandler.handle(createFutureResponse(
              PostAuthnLogEventsResponse.respond204WithTextPlain(String.format(MESSAGE_LOG_EVENT_IS_DISABLED, eventCode))));
            return;
          }

          JsonObject loggingEventJson = JsonObject.mapFrom(logEvent);
          logStorageService.createEvent(vTenantId, loggingEventJson,
            storageHandler -> {
              if (storageHandler.failed()) {
                String errorMessage = storageHandler.cause().getMessage();
                asyncHandler.handle(createFutureResponse(
                  PostAuthnLogEventsResponse.respond500WithTextPlain(errorMessage)));
                return;
              }
              LogResponse response = getResponseEntity(storageHandler, LogResponse.class);
              asyncHandler.handle(createFutureResponse(
                PostAuthnLogEventsResponse.respond201WithApplicationJson(response)));
            });
        })
      );
    } catch (Exception ex) {
      String errorMessage = String.format(ERROR_RUNNING_VERTICLE, "postAuthnLogEvents", ex.getMessage());
      logger.error(errorMessage, ex);
      asyncHandler.handle(createFutureResponse(PostAuthnLogEventsResponse.respond500WithTextPlain(errorMessage)));
    }
  }

  @Override
  public void deleteAuthnLogEventsById(String userId, Map<String, String> requestHeaders,
                                       Handler<AsyncResult<Response>> asyncHandler, Context context) {
    try {
      JsonObject headers = JsonObject.mapFrom(requestHeaders);
      context.runOnContext(contextHandler ->
        configurationService.getEnableConfigurations(vTenantId, headers, serviceHandler -> {
          if (serviceHandler.failed()) {
            String errorMessage = serviceHandler.cause().getMessage();
            asyncHandler.handle(createFutureResponse(
              DeleteAuthnLogEventsByIdResponse.respond500WithTextPlain(errorMessage)));
            return;
          }
          ConfigResponse responseEntity = getResponseEntity(serviceHandler, ConfigResponse.class);
          if (!responseEntity.getEnabled()) {
            asyncHandler.handle(createFutureResponse(
              DeleteAuthnLogEventsByIdResponse.respond204WithTextPlain(MESSAGE_LOG_CONFIGURATION_IS_DISABLED)));
            return;
          }

          logStorageService.deleteEventByUserId(vTenantId, userId,
            storageHandler -> {
              if (storageHandler.failed()) {
                String errorMessage = storageHandler.cause().getMessage();
                asyncHandler.handle(createFutureResponse(
                  DeleteAuthnLogEventsByIdResponse.respond500WithTextPlain(errorMessage)));
                return;
              }
              JsonObject jsonObject = storageHandler.result();
              Boolean isNotFound = Optional.ofNullable(jsonObject.getBoolean(VALUE_IS_NOT_FOUND)).orElse(false);
              if (isNotFound) {
                String message = String.format(ERROR_EVENT_CONFIG_NOT_FOUND, "userId", userId);
                logger.debug(message);
                asyncHandler.handle(createFutureResponse(
                  DeleteAuthnLogEventsByIdResponse.respond404WithTextPlain(message)));
                return;
              }

              LogResponse response = getResponseEntity(storageHandler, LogResponse.class);
              asyncHandler.handle(createFutureResponse(
                DeleteAuthnLogEventsByIdResponse.respond200WithApplicationJson(response)));
            });
        })
      );
    } catch (Exception ex) {
      String errorMessage = String.format(ERROR_RUNNING_VERTICLE, "deleteAuthnLogEventsById", ex.getMessage());
      logger.error(errorMessage, ex);
      asyncHandler.handle(createFutureResponse(DeleteAuthnLogEventsByIdResponse.respond500WithTextPlain(errorMessage)));
    }
  }

  @Override
  public void getAuthnCredentialsExistence(String userId, Map<String, String> okapiHeaders,
                                           Handler<AsyncResult<Response>> asyncResultHandler,
                                           Context vertxContext) {
    vertxContext.runOnContext(v -> {
      try {
        Future<JsonObject> passwordExistenceFuture = Future.future();
        passwordStorageService.getPasswordExistence(userId, vTenantId, passwordExistenceFuture.completer());
        passwordExistenceFuture
          .map(JsonObject::encode)
          .map(Response::ok)
          .map(responseBuilder -> responseBuilder.type(MediaType.APPLICATION_JSON_TYPE))
          .map(Response.ResponseBuilder::build)
          .otherwise(throwable -> {
            logger.error(throwable.getMessage(), throwable);
            return Response.serverError().entity(INTERNAL_ERROR).build();
          })
          .setHandler(asyncResultHandler);
      } catch (Exception e) {
        String message = e.getLocalizedMessage();
        logger.error(message, e);
        asyncResultHandler.handle(Future.succeededFuture(PostAuthnUpdateResponse
          .respond500WithTextPlain(message)));
      }
    });
  }

  private void testForFile(String path) {
    URL u = LoginAPI.class.getClassLoader().getResource(path);
    if(u == null) {
      throw(new MissingResourceException(path, LoginAPI.class.getName(), path));
    }
  }

  @Override
  public void postAuthnUpdate(String userAgent, String xForwardedFor, UpdateCredentials entity,//NOSONAR
                              Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler,
                              Context vertxContext) {
    vertxContext.runOnContext(v -> {
      try {
        Future<JsonObject> userVerifiedFuture;
        String tenantId = getTenant(okapiHeaders);
        String okapiURL = okapiHeaders.get(OKAPI_URL_HEADER);
        String requestToken = okapiHeaders.get(OKAPI_TOKEN_HEADER);
        if(requestToken == null) {
          logger.error("Missing request token");
          asyncResultHandler.handle(Future.succeededFuture(PostAuthnUpdateResponse
              .respond400WithTextPlain("Missing Okapi token header")));
          return;
        }
        if(entity.getUserId() == null && entity.getUsername() == null) {
          logger.error("No username or userId provided for login attempt");
          asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
              .respond400WithTextPlain("You must provide a username or userId")));
          return;
        }
        if(entity.getNewPassword() == null) {
          asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
              .respond400WithTextPlain("You must provide a new password")));
          return;
        }
        if(entity.getUserId() != null && !requireActiveUser) {
          logger.debug("No need to look up user id");
          userVerifiedFuture = Future.succeededFuture(new JsonObject()
              .put("id", entity.getUserId()).put(ACTIVE, true)
              .put(USERNAME, "__undefined__"));
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
                PostAuthnUpdateResponse.respond400WithTextPlain(getErrorResponse(
                errMsg))));
          } else {
            JsonObject userEntity = verifyResult.result();
            checkValidLogin(userEntity.getString("id"), entity.getPassword(),
                tenantId, vertxContext).setHandler(checkLoginResult -> {
              if(checkLoginResult.failed()) {
                String message = checkLoginResult.cause().getLocalizedMessage();
                logger.error(message);
                asyncResultHandler.handle(Future.succeededFuture(
                    PostAuthnUpdateResponse.respond500WithTextPlain(message)));
              } else if(!checkLoginResult.result()) { //Failed login, 401
                asyncResultHandler.handle(Future.succeededFuture(
                    PostAuthnUpdateResponse.respond401WithTextPlain("Invalid credentials")));
              } else { //Password checks out, we can proceed
                Credential newCred = makeCredentialObject(null, userEntity.getString("id"),
                  entity.getNewPassword());

                Map<String, String> requestHeaders = new HashMap<>(okapiHeaders);
                requestHeaders.put(HttpHeaders.USER_AGENT, userAgent);
                requestHeaders.put(X_FORWARDED_FOR_HEADER, xForwardedFor);

                passwordStorageService.updateCredential(JsonObject.mapFrom(newCred), requestHeaders,
                  updateCredResult -> {
                    if (updateCredResult.failed()) {
                      String message = updateCredResult.cause().getLocalizedMessage();
                      logger.error(message);
                      asyncResultHandler.handle(Future.succeededFuture(
                        PostAuthnUpdateResponse.respond500WithTextPlain(message)));
                    } else {
                      // after succesfull change password skip login attempts counter
                      PostgresClient pgClient = PostgresClient.getInstance(vertxContext.owner(), tenantId);
                      loginAttemptsHelper.getLoginAttemptsByUserId(userEntity.getString("id"), pgClient)
                        .compose(attempts ->
                          loginAttemptsHelper.onLoginSuccessAttemptHandler(userEntity, requestHeaders, attempts))
                        .setHandler(event -> {
                          if (event.failed()) {
                            asyncResultHandler.handle(Future.succeededFuture(Authn.PostAuthnLoginResponse
                              .respond500WithTextPlain(INTERNAL_ERROR)));
                          } else {
                            asyncResultHandler.handle(Future.succeededFuture(PostAuthnUpdateResponse.respond204()));
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
            .respond500WithTextPlain(message)));
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
        .setOperation(Criteria.OP_EQUAL)
        .setValue(userId);
    pgClient.get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(credCrit),
        true, getReply -> {
      if(getReply.failed()) {
        validLoginFuture.fail(getReply.cause());
      } else {
        List<Credential> credList = getReply.result().getResults();
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

  public static Errors getErrors(String errorMessage, String errorCode, Pair... pairs) {
    Errors errors = new Errors();
    Error error = new Error();
    error.setMessage(errorMessage);
    error.setCode(errorCode);
    error.setType(TYPE_ERROR);
    List<Parameter> params = new ArrayList<>();
    if (pairs.length > 0) {
      for (Pair p : pairs) {
        if (p.getKey() != null && p.getValue() != null) {
          Parameter param = new Parameter();
          param.setKey((String) p.getKey());
          param.setValue((String) p.getValue());
          params.add(param);
        }
      }
    }
    error.withParameters(params);
    errors.setErrors(Collections.singletonList(error));
    return errors;
  }
}
