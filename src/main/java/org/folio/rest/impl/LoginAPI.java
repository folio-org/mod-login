package org.folio.rest.impl;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.util.LoginAttemptsHelper.TABLE_NAME_LOGIN_ATTEMPTS;
import static org.folio.util.LoginAttemptsHelper.buildCriteriaForUserAttempts;
import static org.folio.util.LoginConfigUtils.EVENT_CONFIG_PROXY_CONFIG_ADDRESS;
import static org.folio.util.LoginConfigUtils.EVENT_CONFIG_PROXY_STORY_ADDRESS;
import static org.folio.util.LoginConfigUtils.PW_CONFIG_PROXY_STORY_ADDRESS;
import static org.folio.util.LoginConfigUtils.VALUE_IS_NOT_FOUND;
import static org.folio.util.LoginConfigUtils.createFutureResponse;
import static org.folio.util.LoginConfigUtils.getResponseEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.vertx.ext.web.client.predicate.ResponsePredicate;
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.ConfigResponse;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.Error;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.LogEvent;
import org.folio.rest.jaxrs.model.LogEvents;
import org.folio.rest.jaxrs.model.LogResponse;
import org.folio.rest.jaxrs.model.LoginAttempts;
import org.folio.rest.jaxrs.model.LoginCredentials;
import org.folio.rest.jaxrs.model.LoginResponse;
import org.folio.rest.jaxrs.model.Parameter;
import org.folio.rest.jaxrs.model.Password;
import org.folio.rest.jaxrs.model.PasswordCreate;
import org.folio.rest.jaxrs.model.PasswordReset;
import org.folio.rest.jaxrs.model.PasswordValid;
import org.folio.rest.jaxrs.model.ResponseCreateAction;
import org.folio.rest.jaxrs.model.ResponseResetAction;
import org.folio.rest.jaxrs.model.UpdateCredentials;
import org.folio.rest.jaxrs.resource.Authn;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.tools.utils.TenantTool;
import org.folio.rest.tools.utils.ValidationHelper;
import org.folio.services.ConfigurationService;
import org.folio.services.LogStorageService;
import org.folio.services.PasswordStorageService;
import org.folio.util.AuthUtil;
import org.folio.util.LoginAttemptsHelper;
import org.folio.util.LoginConfigUtils;
import org.folio.util.PercentCodec;
import org.folio.util.StringUtil;
import org.folio.util.WebClientFactory;

import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;

/**
 * @author kurt
 */
public class LoginAPI implements Authn {

  private static final String TABLE_NAME_CREDENTIALS = "auth_credentials";
  public static final String X_FORWARDED_FOR_HEADER = "X-Forwarded-For";
  private static final String CREDENTIAL_USERID_FIELD = "'userId'";
  private static final String USER_ID_FIELD = "'userId'";
  private static final String ERROR_RUNNING_VERTICLE = "Error running on verticle for `%s`: %s";
  private static final String ERROR_PW_ACTION_ENTITY_NOT_FOUND = "Password action with ID: `%s` was not found in the db";
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
  private final AuthUtil authUtil = new AuthUtil();
  private boolean suppressErrorResponse = false;
  private boolean requireActiveUser = Boolean.parseBoolean(MODULE_SPECIFIC_ARGS
      .getOrDefault("require.active", "true"));

  private static final Logger logger = LogManager.getLogger(LoginAPI.class);

  private String vTenantId;
  private LogStorageService logStorageService;
  private ConfigurationService configurationService;
  private PasswordStorageService passwordStorageService;
  private LoginAttemptsHelper loginAttemptsHelper;

  private final Vertx vertx;

  public LoginAPI(Vertx vertx, String tenantId) {
    this.vertx = vertx;
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

  private String getTenant(Map<String, String> headers) {
    return TenantTool.calculateTenantId(headers.get(XOkapiHeaders.TENANT));
  }

  static String buildUserLookupURL(String okapiURL, String username, String userId) {
    String query;
    if (username != null) {
      query = "username==" + StringUtil.cqlEncode(username);
    } else {
      query = "id==" + StringUtil.cqlEncode(userId);
    }
    return okapiURL + "/users?query=" + PercentCodec.encode(query);
  }

  private JsonObject extractUserFromLookupResponse(HttpResponse<Buffer> res, String requestURL, String username) throws UserLookupException {
    if (res.statusCode() != 200) {
      String message = "Error looking up user at url '" + requestURL + "' Expected status code 200, got '" + res.statusCode()
          + "' :" + res.bodyAsString();
      throw new UserLookupException(message);
    }
    JsonObject lookupResult = res.bodyAsJsonObject();
    if (!lookupResult.containsKey("totalRecords") || !lookupResult.containsKey("users")) {
      throw new UserLookupException("Error, missing field(s) 'totalRecords' and/or 'users' in user response object");
    }
    int recordCount = lookupResult.getInteger("totalRecords");
    if (recordCount > 1) {
      throw new UserLookupException("Bad results from username");
    }
    if (recordCount == 0) {
      throw new UserLookupException("No user found by username " + username);
    }
    return lookupResult.getJsonArray("users").getJsonObject(0);
  }

  /*
    Query the mod-users module to determine whether or not the username is
    valid for login
  */
  private Future<JsonObject> lookupUser(String username, String userId, String tenant,
      final String okapiURL, String requestToken) {
    final String requestURL = buildUserLookupURL(okapiURL, username, userId);
    HttpRequest<Buffer> request = WebClientFactory.getWebClient(vertx).getAbs(requestURL);
    request.putHeader(XOkapiHeaders.TENANT, tenant)
      .putHeader(XOkapiHeaders.TOKEN, requestToken);
    return request
      .expect(ResponsePredicate.JSON)
      .send()
      .map(res -> extractUserFromLookupResponse(res, requestURL, username));
  }

  private Future<String> fetchToken(JsonObject payload, String tenant,
    String okapiURL, String requestToken) {
    HttpRequest<Buffer> request = WebClientFactory.getWebClient(vertx).postAbs(okapiURL + "/token");

    request.putHeader(XOkapiHeaders.TENANT, tenant)
      .putHeader(XOkapiHeaders.TOKEN, requestToken);

    return request.sendJson(new JsonObject().put("payload", payload)).map(response -> {
      if (response.statusCode() != 200 && response.statusCode() != 201) {
        throw new RuntimeException("Got response " + response.statusCode() + " fetching token");
      }
      String token = response.statusCode() == 200
        ? response.getHeader(XOkapiHeaders.TOKEN)
        : response.bodyAsJsonObject().getString("token");
      if (token == null) {
        throw new RuntimeException(String.format("Got response %s fetching token, but content is null",
          response.statusCode()));
      }
      logger.debug("Got token " + token + " from authz");
      return token;
    });
  }

  private Future<String> fetchRefreshToken(String userId, String sub, String tenant,
    String okapiURL, String requestToken) {
    HttpRequest<Buffer> request = WebClientFactory.getWebClient(vertx).postAbs(okapiURL + "/refreshtoken");
    request.putHeader(XOkapiHeaders.TENANT, tenant)
      .putHeader(XOkapiHeaders.TOKEN, requestToken);

    JsonObject payload = new JsonObject().put("userId", userId).put("sub", sub);
    return request
      .expect(ResponsePredicate.SC_CREATED)
      .expect(ResponsePredicate.JSON)
      .sendJsonObject(payload)
      .map(response -> response.bodyAsJsonObject().getString("refreshToken")
      );
  }

  @Override
  public void getAuthnLoginAttemptsById(String id, Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    try {
      String tenantId = getTenant(okapiHeaders);
      try {
        PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_LOGIN_ATTEMPTS, LoginAttempts.class, buildCriteriaForUserAttempts(id), true,  getReply -> {
          if (getReply.failed()) {
            logger.debug(POSTGRES_ERROR_GET + getReply.cause().getLocalizedMessage());
            asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
          } else {
            List<LoginAttempts> attemptsList = getReply.result().getResults();
            if (attemptsList.isEmpty()) {
              asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.respond404WithTextPlain("No user login attempts found for id " + id)));
            } else {
              asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.respond200WithApplicationJson(attemptsList.get(0))));
            }
          }
        });
      } catch(Exception e) {
        logger.debug(POSTGRES_ERROR + e.getLocalizedMessage());
        asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
      }
    } catch(Exception e) {
      logger.debug(VERTX_CONTEXT_ERROR + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(GetAuthnLoginAttemptsByIdResponse.respond500WithTextPlain(INTERNAL_ERROR)));
    }
  }

  @Override
  public void postAuthnLogin(String userAgent, String xForwardedFor,
      LoginCredentials entity, Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    try {
      String tenantId = getTenant(okapiHeaders);
      String okapiURL = okapiHeaders.get(XOkapiHeaders.URL);
      if (okapiURL == null) {
        asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
            .respond400WithTextPlain("Missing " + XOkapiHeaders.URL + " header")));
        return;
      }
      String requestToken = okapiHeaders.get(XOkapiHeaders.TOKEN);
      if (requestToken == null) {
        logger.error("Missing request token");
        asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
            .respond400WithTextPlain("Missing Okapi token header")));
        return;
      }
      Future<JsonObject> userVerified;
      if (entity.getUserId() == null && entity.getUsername() == null) {
        logger.error("No username or userId provided for login attempt");
        asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
            .respond400WithTextPlain("You must provide a username or userId")));
        return;
      }
      if (entity.getPassword() == null) {
        logger.error("No password provided for login attempt");
        asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
            .respond400WithTextPlain("You must provide a password")));
        return;
      }
      if (entity.getUserId() != null && !requireActiveUser) {
        logger.debug("No need to look up user id");
        userVerified = Future.succeededFuture(new JsonObject()
            .put("id", entity.getUserId()).put(ACTIVE, true)
            .put(USERNAME, "__undefined__"));
      } else {
        logger.debug("Need to look up user id");
        if (entity.getUserId() != null) {
          userVerified = lookupUser(null, entity.getUserId(), tenantId, okapiURL,
              requestToken);
        } else {
          userVerified = lookupUser(entity.getUsername(), null, tenantId, okapiURL,
              requestToken);
        }
      }
      userVerified.onComplete(verifyResult -> {
        if (verifyResult.failed()) {
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
            if (!userObject.containsKey("id")) {
              logger.error("No 'id' key in returned user object");
              asyncResultHandler.handle(Future.succeededFuture(
                  PostAuthnLoginResponse.respond500WithTextPlain(
                      "No user id could be found")));
              return;
            }
            if (requireActiveUser) {
              boolean foundActive = userObject.containsKey(ACTIVE) && userObject.getBoolean(ACTIVE);
              if (!foundActive) {
                logger.error("User could not be verified as active");
                asyncResultHandler.handle(Future.succeededFuture(
                    PostAuthnLoginResponse.respond422WithApplicationJson(
                        getErrors("User must be flagged as active", CODE_USER_BLOCKED))
                ));
                return;
              }
            }
            Criteria useridCrit = new Criteria();
            useridCrit.addField(CREDENTIAL_USERID_FIELD);
            useridCrit.setOperation("=");
            useridCrit.setVal(userObject.getString("id"));
            PostgresClient.getInstance(vertxContext.owner(), tenantId).get(
                TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(useridCrit),
                true, getReply-> {
                  if (getReply.failed()) {
                    logger.error("Error in postgres get operation: " +
                        getReply.cause().getLocalizedMessage());
                    asyncResultHandler.handle(Future.succeededFuture(
                        PostAuthnLoginResponse.respond500WithTextPlain(
                            INTERNAL_ERROR)));
                  } else {
                    try {
                      List<Credential> credList = getReply.result().getResults();
                      if (credList.isEmpty()) {
                        logger.error("No matching credentials found for userid " + userObject.getString("id"));
                        asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.respond400WithTextPlain("No credentials match that login")));
                      } else {
                        Credential userCred = credList.get(0);
                        if (userCred.getHash() == null || userCred.getSalt() == null) {
                          String message = "Error retrieving stored hash and salt from credentials";
                          logger.error(message);
                          asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.respond500WithTextPlain(message)));
                          return;
                        }
                        logger.debug("Testing hash for credentials for user with id '" + userObject.getString("id") + "'");
                        String testHash = authUtil.calculateHash(entity.getPassword(), userCred.getSalt());
                        String sub;
                        if (userCred.getHash().equals(testHash)) {
                          JsonObject payload = new JsonObject();
                          if (userObject.containsKey(USERNAME)) {
                            sub = userObject.getString(USERNAME);
                          } else {
                            sub = userObject.getString("id");
                          }
                          payload.put("sub", sub);
                          if (!userObject.isEmpty()) {
                            payload.put("user_id", userObject.getString("id"));
                          }
                          Future<String> fetchTokenFuture;
                          Future<String> fetchRefreshTokenFuture;
                          String fetchTokenFlag = RestVerticle.MODULE_SPECIFIC_ARGS.get("fetch.token");
                          if (fetchTokenFlag != null && fetchTokenFlag.equals("no")) {
                            fetchTokenFuture = Future.succeededFuture("dummytoken");
                          } else {
                            logger.debug("Fetching token from authz with payload " + payload.encode());
                            fetchTokenFuture = fetchToken(payload, tenantId, okapiURL, requestToken);
                          }
                          fetchRefreshTokenFuture = fetchRefreshToken(userObject.getString("id"),
                              sub, tenantId, okapiURL, requestToken);
                          CompositeFuture compositeFuture = CompositeFuture.join(fetchTokenFuture,
                              fetchRefreshTokenFuture);

                          compositeFuture.onComplete(fetchTokenRes -> {
                            if (fetchTokenFuture.failed()) {
                              String errMsg = "Error fetching token: " + fetchTokenFuture.cause().getLocalizedMessage();
                              logger.error(errMsg);
                              asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.respond500WithTextPlain(getErrorResponse(errMsg))));
                            } else {
                              String refreshToken = null;
                              if (fetchRefreshTokenFuture.failed()) {
                                logger.error(String.format("Error getting refresh token: %s",
                                    fetchRefreshTokenFuture.cause().getLocalizedMessage()));
                              } else {
                                refreshToken = fetchRefreshTokenFuture.result();
                              }
                              PostgresClient pgClient = PostgresClient.getInstance(vertxContext.owner(), tenantId);
                              // after succesful login skip login attempts counter
                              String finalRefreshToken = refreshToken;
                              Map<String, String> requestHeaders = createRequestHeader(okapiHeaders, userAgent, xForwardedFor);
                              loginAttemptsHelper.getLoginAttemptsByUserId(userObject.getString("id"), pgClient)
                                  .compose(attempts ->
                                      loginAttemptsHelper.onLoginSuccessAttemptHandler(userObject, requestHeaders, attempts))
                                  .onComplete(reply -> {
                                    if (reply.failed()) {
                                      asyncResultHandler.handle(Future.succeededFuture(
                                          PostAuthnLoginResponse.respond500WithTextPlain(INTERNAL_ERROR)));
                                    } else {
                                      //Append token as header to result
                                      String authToken = fetchTokenFuture.result();
                                      LoginResponse response = new LoginResponse()
                                          .withOkapiToken(authToken)
                                          .withRefreshToken(finalRefreshToken);
                                      asyncResultHandler.handle(Future.succeededFuture(
                                          PostAuthnLoginResponse.respond201WithApplicationJson(response,
                                              PostAuthnLoginResponse.headersFor201()
                                                  .withXOkapiToken(authToken)
                                                  .withRefreshtoken(finalRefreshToken))));
                                    }
                                  });

                            }
                          });
                        } else {
                          PostgresClient pgClient = PostgresClient.getInstance(vertxContext.owner(), tenantId);
                          Map<String, String> requestHeaders = createRequestHeader(okapiHeaders, userAgent, xForwardedFor);
                          loginAttemptsHelper.getLoginAttemptsByUserId(userObject.getString("id"), pgClient)
                              .compose(attempts ->
                                  loginAttemptsHelper.onLoginFailAttemptHandler(userObject, requestHeaders, attempts))
                              .onComplete(errors -> {
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
    } catch(Exception e) {
      logger.debug("Error running on verticle for postAuthnLogin: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse.respond500WithTextPlain(INTERNAL_ERROR)));
    }
  }

  @Override
  public void postAuthnCredentials(LoginCredentials entity,
      Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    try {
      String tenantId = getTenant(okapiHeaders);
      String requestToken = okapiHeaders.get(XOkapiHeaders.TOKEN);
      if (entity.getPassword() == null || entity.getPassword().isEmpty()) {
        asyncResultHandler.handle(Future.succeededFuture(
            PostAuthnCredentialsResponse.respond422WithApplicationJson(
                ValidationHelper.createValidationErrorMessage(
                    CREDENTIAL_USERID_FIELD, entity.getUserId(), "Password is missing or empty"))));
        return;
      }
      Future<UUID> userIdFuture;
      if (entity.getUserId() != null) {
        // in reality there should be a lookup of user with the id here,
        // We leave it as is to preserve compatibility as one could add credentials
        // before adding a user.
        userIdFuture = Future.succeededFuture(UUID.fromString(entity.getUserId()));
      } else {
        String okapiURL = okapiHeaders.get(XOkapiHeaders.URL);
        if (okapiURL == null) {
          asyncResultHandler.handle(Future.succeededFuture(
              PostAuthnCredentialsResponse.respond400WithTextPlain("Missing " + XOkapiHeaders.URL + " header")));
          return;
        }
        userIdFuture = lookupUser(entity.getUsername(), null, tenantId, okapiURL,
            requestToken).map(userObj -> UUID.fromString(userObj.getString("id")));
      }
      userIdFuture.onComplete(verifyRes -> {
        if (verifyRes.failed()) {
          String message = "Error looking up user: " + verifyRes.cause()
              .getLocalizedMessage();
          logger.error(message, verifyRes.cause());
          asyncResultHandler.handle(Future.succeededFuture(PostAuthnCredentialsResponse
              .respond400WithTextPlain(message)));
        } else {
          UUID userId = verifyRes.result();
          Criteria userIdCrit = new Criteria();
          userIdCrit.addField(CREDENTIAL_USERID_FIELD);
          userIdCrit.setOperation("=");
          userIdCrit.setVal(userId.toString());
          try {
            PostgresClient.getInstance(vertxContext.owner(), tenantId).get(
                TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(userIdCrit),
                true, getCredReply -> {
                  try {
                    if (getCredReply.failed()) {
                      String message = getCredReply.cause().getLocalizedMessage();
                      logger.error(message);
                      asyncResultHandler.handle(Future.succeededFuture(
                          PostAuthnCredentialsResponse.respond500WithTextPlain(message)));
                    } else {
                      List<Credential> credList = getCredReply.result().getResults();
                      if (!credList.isEmpty()) {
                        String message = "There already exists credentials for user id '"
                            + userId + "'";
                        logger.error(message);
                        asyncResultHandler.handle(Future.succeededFuture(
                            PostAuthnCredentialsResponse.respond422WithApplicationJson(
                                ValidationHelper.createValidationErrorMessage(
                                    CREDENTIAL_USERID_FIELD, userId.toString(), message))));
                      } else {
                        //Now we can create a new Credential
                        Credential credential = new Credential();
                        credential.setId(UUID.randomUUID().toString());
                        credential.setUserId(userId.toString());
                        credential.setSalt(authUtil.getSalt());
                        credential.setHash(authUtil.calculateHash(entity.getPassword(),
                            credential.getSalt()));
                        //And save it
                        PostgresClient pgClient = PostgresClient.getInstance(
                            vertxContext.owner(), tenantId);
                        pgClient.save(TABLE_NAME_CREDENTIALS, credential.getId(),
                            credential, saveReply -> {
                              if (saveReply.failed()) {
                                String message = "Saving record failed: "
                                    + saveReply.cause().getLocalizedMessage();
                                logger.error(message, saveReply.cause());
                                asyncResultHandler.handle(Future.succeededFuture(
                                    PostAuthnCredentialsResponse.respond500WithTextPlain(message)));
                              } else {
                                asyncResultHandler.handle(Future.succeededFuture(
                                    PostAuthnCredentialsResponse.respond201()));
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
    } catch(Exception e) {
      logger.error(VERTX_CONTEXT_ERROR + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(PostAuthnCredentialsResponse.respond500WithTextPlain(INTERNAL_ERROR)));
    }
  }

  @Override
  public void deleteAuthnCredentials(String userId, Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {

    String tenantId = getTenant(okapiHeaders);
    Criteria crit = new Criteria();
    crit.addField(USER_ID_FIELD);
    crit.setOperation("=");
    crit.setVal(userId);

    PostgresClient.getInstance(vertxContext.owner(), tenantId).get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(crit), true, getReply -> {
      if (getReply.failed()) {
        logger.debug(POSTGRES_ERROR_GET + getReply.cause().getLocalizedMessage());
        asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsResponse.respond500WithTextPlain(INTERNAL_ERROR)));
        return;
      }

      List<Credential> credList = getReply.result().getResults();
      if (credList.isEmpty()) {
        asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsResponse.respond404WithTextPlain("No credentials for userId " + userId + " found")));
        return;
      }

      PostgresClient.getInstance(vertxContext.owner(), tenantId).delete(TABLE_NAME_CREDENTIALS, new Criterion(crit), deleteReply-> {
        if (deleteReply.failed()) {
          logger.debug(POSTGRES_ERROR_GET + deleteReply.cause().getLocalizedMessage());
          asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsResponse.respond500WithTextPlain(INTERNAL_ERROR)));
          return;
        }
        asyncResultHandler.handle(Future.succeededFuture(DeleteAuthnCredentialsResponse.respond204()));
      });
    });
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
      passwordStorageService.isPasswordPreviouslyUsed(JsonObject.mapFrom(password),
          LoginConfigUtils.encodeJsonHeaders(okapiHeaders), used -> {
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
      });
    } catch(Exception e) {
      logger.debug(VERTX_CONTEXT_ERROR + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(
          PostAuthnPasswordRepeatableResponse.respond500WithTextPlain(INTERNAL_ERROR)));
    }
  }

  static Map<String,String> createRequestHeader(Map<String, String> okapiHeaders, String userAgent, String forwardedFor) {
    Map<String,String> nMap = new CaseInsensitiveMap<>(okapiHeaders);
    nMap.put(HttpHeaders.USER_AGENT, userAgent);
    nMap.put(X_FORWARDED_FOR_HEADER, forwardedFor);
    return nMap;
  }

  @Override
  public void postAuthnResetPassword(String userAgent, String xForwardedFor,
      PasswordReset entity, Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncHandler, Context context) {
    try {
      JsonObject passwordResetJson = JsonObject.mapFrom(entity);
      Map<String, String> requestHeaders = createRequestHeader(okapiHeaders, userAgent, xForwardedFor);
      passwordStorageService.resetPassword(LoginConfigUtils.encodeJsonHeaders(requestHeaders), passwordResetJson,
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
          });
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
          });
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
          });
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
      configurationService.getEnableConfigurations(vTenantId, LoginConfigUtils.encodeJsonHeaders(requestHeaders), serviceHandler -> {
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
          }
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
      configurationService.getEnableConfigurations(vTenantId, LoginConfigUtils.encodeJsonHeaders(requestHeaders), serviceHandler -> {
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
          }
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
      configurationService.getEnableConfigurations(vTenantId, LoginConfigUtils.encodeJsonHeaders(requestHeaders), serviceHandler -> {
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
          }
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
    try {
      Promise<Response> promise = Promise.promise();
      passwordStorageService.getPasswordExistence(userId, vTenantId, reply -> {
        try {
          Response resp = Response.ok(reply.result()
                  .encode(), MediaType.APPLICATION_JSON_TYPE)
              .build();
          promise.complete(resp);
        } catch (Exception e) {
          logger.error(e.getMessage(), e);
          promise.complete(Response.serverError().entity(INTERNAL_ERROR).build());
        }
      });
      promise.future().onComplete(asyncResultHandler);
    } catch (Exception e) {
      String message = e.getLocalizedMessage();
      logger.error(message, e);
      asyncResultHandler.handle(Future.succeededFuture(PostAuthnUpdateResponse
          .respond500WithTextPlain(message)));
    }
  }

  @Override
  public void postAuthnUpdate(String userAgent, String xForwardedFor, UpdateCredentials entity,
      Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler,
      Context vertxContext) {
    try {
      Future<JsonObject> userVerifiedFuture;
      String tenantId = getTenant(okapiHeaders);
      String okapiURL = okapiHeaders.get(XOkapiHeaders.URL);
      if (okapiURL == null) {
        asyncResultHandler.handle(Future.succeededFuture(PostAuthnUpdateResponse
            .respond400WithTextPlain("Missing " + XOkapiHeaders.URL + " header")));
        return;
      }
      String requestToken = okapiHeaders.get(XOkapiHeaders.TOKEN);
      if (requestToken == null) {
        logger.error("Missing request token");
        asyncResultHandler.handle(Future.succeededFuture(PostAuthnUpdateResponse
            .respond400WithTextPlain("Missing Okapi token header")));
        return;
      }
      if (entity.getUserId() == null && entity.getUsername() == null) {
        logger.error("No username or userId provided for login attempt");
        asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
            .respond400WithTextPlain("You must provide a username or userId")));
        return;
      }
      if (entity.getNewPassword() == null || entity.getNewPassword().isEmpty()) {
        asyncResultHandler.handle(Future.succeededFuture(PostAuthnLoginResponse
            .respond400WithTextPlain("You must provide a new password that isn't empty")));
        return;
      }
      if (entity.getUserId() != null && !requireActiveUser) {
        logger.debug("No need to look up user id");
        userVerifiedFuture = Future.succeededFuture(new JsonObject()
            .put("id", entity.getUserId()).put(ACTIVE, true)
            .put(USERNAME, "__undefined__"));
      } else {
        logger.debug("Need to look up user id");
        if (entity.getUserId() != null) {
          userVerifiedFuture = lookupUser(null, entity.getUserId(), tenantId, okapiURL,
              requestToken);
        } else {
          userVerifiedFuture = lookupUser(entity.getUsername(), null, tenantId, okapiURL,
              requestToken);
        }
      }
      userVerifiedFuture.onComplete(verifyResult -> {
        if (verifyResult.failed()) {
          String errMsg = "Error verifying user existence: " + verifyResult
              .cause().getLocalizedMessage();
          logger.error(errMsg);
          asyncResultHandler.handle(Future.succeededFuture(
              PostAuthnUpdateResponse.respond400WithTextPlain(getErrorResponse(
                  errMsg))));
        } else {
          JsonObject userEntity = verifyResult.result();
          checkValidLogin(userEntity.getString("id"), entity.getPassword(),
              tenantId, vertxContext).onComplete(checkLoginResult -> {
            if (checkLoginResult.failed()) {
              String message = checkLoginResult.cause().getLocalizedMessage();
              logger.error(message);
              asyncResultHandler.handle(Future.succeededFuture(
                  PostAuthnUpdateResponse.respond500WithTextPlain(message)));
            } else if (!checkLoginResult.result()) { //Failed login, 401
              asyncResultHandler.handle(Future.succeededFuture(
                  PostAuthnUpdateResponse.respond401WithTextPlain("Invalid credentials")));
            } else { //Password checks out, we can proceed
              Credential newCred = makeCredentialObject(null, userEntity.getString("id"),
                  entity.getNewPassword());

              Map<String, String> requestHeaders = createRequestHeader(okapiHeaders, userAgent, xForwardedFor);
              passwordStorageService.updateCredential(JsonObject.mapFrom(newCred), LoginConfigUtils.encodeJsonHeaders(requestHeaders),
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
                          .onComplete(event -> {
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
  }

  private Future<Boolean> checkValidLogin(String userId, String password,
      String tenantId, Context vertxContext) {
    Promise<Boolean> validLoginPromise = Promise.promise();
    PostgresClient pgClient = PostgresClient.getInstance(vertxContext.owner(),
        tenantId);
    //Get credentials
    Criteria credCrit = new Criteria()
        .addField(CREDENTIAL_USERID_FIELD)
        .setOperation("=")
        .setVal(userId);
    pgClient.get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(credCrit),
        true, getReply -> {
      if (getReply.failed()) {
        validLoginPromise.fail(getReply.cause());
      } else {
        List<Credential> credList = getReply.result().getResults();
        if (credList.isEmpty()) {
          validLoginPromise.fail("No valid credential for that userId found");
        }
        Credential userCred = credList.get(0);
        String calculatedHash = authUtil.calculateHash(password, userCred.getSalt());
        if (calculatedHash.equals(userCred.getHash())) {
          validLoginPromise.complete(Boolean.TRUE);
        } else {
          validLoginPromise.complete(Boolean.FALSE);
        }
      }
    });
    return validLoginPromise.future();
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

  public static Errors getErrors(String errorMessage, String errorCode,
    Pair<String, String> pair) {

    Errors errors = new Errors();
    Error error = getError(errorMessage, errorCode);
    List<Parameter> params = new ArrayList<>();
    if (pair.getKey() != null && pair.getValue() != null) {
      Parameter param = new Parameter();
      param.setKey(pair.getKey());
      param.setValue(pair.getValue());
      params.add(param);
    }
    error.withParameters(params);
    errors.setErrors(Collections.singletonList(error));
    return errors;
  }

  public static Errors getErrors(String errorMessage, String errorCode) {
    Errors errors = new Errors();
    Error error = getError(errorMessage, errorCode);
    errors.setErrors(Collections.singletonList(error));
    return errors;
  }

  private static Error getError(String errorMessage, String errorCode) {
    Error error = new Error();
    error.setMessage(errorMessage);
    error.setCode(errorCode);
    error.setType(TYPE_ERROR);
    return error;
  }

  public static class UserLookupException extends RuntimeException {
    private static final long serialVersionUID = 5473696882222270905L;

    public UserLookupException(String message) {
      super(message);
    }
  }
}
