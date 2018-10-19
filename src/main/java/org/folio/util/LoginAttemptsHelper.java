package org.folio.util;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.sql.UpdateResult;
import org.apache.commons.lang.time.DateFormatUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.jaxrs.model.LoginAttempts;
import org.folio.rest.jaxrs.resource.Authn;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.PostgresClient;

import javax.ws.rs.core.Response;
import java.net.URLEncoder;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.rest.impl.LoginAPI.INTERNAL_ERROR;
import static org.folio.rest.impl.LoginAPI.OKAPI_TENANT_HEADER;
import static org.folio.rest.impl.LoginAPI.OKAPI_TOKEN_HEADER;
import org.folio.rest.persist.interfaces.Results;

/**
 * Helper class that contains static methods which helps with processing Login Attempts business logic
 */
public class LoginAttemptsHelper {

  public static final String LOGIN_ATTEMPTS_SCHEMA_PATH = "ramls/loginAttempts.json";
  public static final String TABLE_NAME_LOGIN_ATTEMPTS = "auth_attempts";
  public static final String LOGIN_ATTEMPTS_CODE = "login.fail.attempts";
  public static final String LOGIN_ATTEMPTS_TO_WARN_CODE = "login.fail.to.warn.attempts";
  public static final String LOGIN_ATTEMPTS_TIMEOUT_CODE = "login.fail.timeout";
  private static final String LOGIN_ATTEMPTS_USERID_FIELD = "'userId'";
  private static final Logger logger = LoggerFactory.getLogger(LoginAttemptsHelper.class);
  private static final String JSON_TYPE = "application/json";
  private static final String VALUE = "value";

  /**
   * Method build criteria for lookup Login Attempts for user by user id
   *
   * @param userId - identifier of user
   * @return - Criterion for search login attempts using user id
   */
  public static Criterion buildCriteriaForUserAttempts(String userId) throws Exception {
    Criteria attemptCrit = new Criteria(LOGIN_ATTEMPTS_SCHEMA_PATH);
    attemptCrit.addField(LOGIN_ATTEMPTS_USERID_FIELD);
    attemptCrit.setOperation(Criteria.OP_EQUAL);
    attemptCrit.setValue(userId);
    return new Criterion(attemptCrit);
  }

  /**
   * @return - async result for saving login attempt
   */
  private static Handler<AsyncResult<String>> saveAttemptHandler(Handler<AsyncResult<Response>> asyncResultHandler) {
    return saveAttempt -> {
      if (saveAttempt.failed()) {
        String message = "Saving record failed: " + saveAttempt.cause().getLocalizedMessage();
        logger.error(message, saveAttempt.cause());
        asyncResultHandler.handle(Future.succeededFuture(
          Authn.PostAuthnLoginResponse.respond500WithTextPlain(message)));
      }
    };
  }

  /**
   * @return - async result for updating login attempt
   */
  private static Handler<AsyncResult<UpdateResult>> updateAttemptHandler(Handler<AsyncResult<Response>> asyncResultHandler) {
    return updateAttempt -> {
      if (updateAttempt.failed()) {
        String message = "Saving record failed: " + updateAttempt.cause().getLocalizedMessage();
        logger.error(message, updateAttempt.cause());
        asyncResultHandler.handle(Future.succeededFuture(
          Authn.PostAuthnLoginResponse.respond500WithTextPlain(message)));
      }
    };
  }


  /**
   * Method save login attempt object to database
   *
   * @param pgClient     - client of postgres database
   * @param loginAttempt - login attempt entity
   */
  private static void saveAttempt(PostgresClient pgClient, LoginAttempts loginAttempt,
                                  Handler<AsyncResult<Response>> asyncResultHandler,
                                  Handler<AsyncResult<String>> replyHandler) {
    try {
      pgClient.save(TABLE_NAME_LOGIN_ATTEMPTS, loginAttempt.getId(), loginAttempt, replyHandler);
    } catch (Exception e) {
      logger.error("Error with postgresclient on saving login attempt during post login request: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(Authn.PostAuthnLoginResponse
        .respond500WithTextPlain(INTERNAL_ERROR)));
    }
  }

  /**
   * Method update login attempt object to database
   *
   * @param pgClient     - client of postgres database
   * @param loginAttempt - login attempt entity for update
   */
  private static void updateAttempt(PostgresClient pgClient, LoginAttempts loginAttempt,
                                    Handler<AsyncResult<Response>> asyncResultHandler,
                                    Handler<AsyncResult<UpdateResult>> replyHandler) {
    try {
      pgClient.update(TABLE_NAME_LOGIN_ATTEMPTS, loginAttempt, loginAttempt.getId(), replyHandler);
    } catch (Exception e) {
      logger.error("Error with postgresclient on saving login attempt during post login request: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(Authn.PostAuthnLoginResponse
        .respond500WithTextPlain(INTERNAL_ERROR)));
    }
  }

  /**
   * Method get login attempt from database by user id and provide callback for use this data
   *
   * @param userId       - user id for find login attempt entity
   * @param pgClient     - postgres client
   * @param replyHandler - async result handler for processing login attempt entity
   */
  public static void getLoginAttemptsByUserId(String userId, PostgresClient pgClient,
                                              Handler<AsyncResult<Response>> asyncResultHandler,
                                              Handler<AsyncResult<Results<LoginAttempts>>> replyHandler) {
    try {
      pgClient.get(TABLE_NAME_LOGIN_ATTEMPTS, LoginAttempts.class, buildCriteriaForUserAttempts(userId),
        true, false, replyHandler);
    } catch (Exception e) {
      logger.error("Error with postgres client on getting login attempt during post login request: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(Authn.PostAuthnLoginResponse
        .respond500WithTextPlain(INTERNAL_ERROR)));
    }
  }

  /**
   * @param userId - user identifier for his login attempts
   * @param count  - initial count of failed login
   * @return - new Login Attempt object
   */
  private static LoginAttempts buildLoginAttemptsObject(String userId, Integer count) {
    LoginAttempts loginAttempt = new LoginAttempts();
    loginAttempt.setId(UUID.randomUUID().toString());
    loginAttempt.setAttemptCount(count);
    loginAttempt.setUserId(userId);
    loginAttempt.setLastAttempt(new Date());
    return loginAttempt;
  }

  /**
   * @param attempts - Login Attempts user object for deciding
   *                 need to block user after fail login or not
   * @return - boolean value that describe need block user or not
   */
  private static Future<Boolean> needToUserBlock(LoginAttempts attempts, OkapiConnectionParams params, JsonObject userObject, Handler<AsyncResult<Response>> asyncResultHandler) {
    Future<Boolean> future = Future.future();
    try {
      getLoginConfig(LOGIN_ATTEMPTS_CODE, params).setHandler(res ->
        getLoginConfig(LOGIN_ATTEMPTS_TIMEOUT_CODE, params).setHandler(handle ->
          getLoginConfig(LOGIN_ATTEMPTS_TO_WARN_CODE, params).setHandler(res1 -> {
        boolean result = false;
        int loginTimeoutConfigValue = 10;
        if (handle.failed()) {
          logger.warn(handle.cause());
          loginTimeoutConfigValue = Integer.parseInt(MODULE_SPECIFIC_ARGS
            .getOrDefault(LOGIN_ATTEMPTS_TIMEOUT_CODE, "10"));
        } else {
          try {
            loginTimeoutConfigValue = Integer.parseInt(handle.result().getString(VALUE));
          } catch (Exception e) {
            logger.error(e);
          }
        }
        int loginFailConfigValue = 5;
        if (res.failed()) {
          logger.warn(res.cause());
          loginFailConfigValue = Integer.parseInt(MODULE_SPECIFIC_ARGS
            .getOrDefault(LOGIN_ATTEMPTS_CODE, "5"));
        } else {
          try {
            loginFailConfigValue = Integer.parseInt(res.result().getString(VALUE));
          } catch (Exception e) {
            logger.error(e);
          }
        }
        int loginFailToWarnValue = 3;
        if (res1.failed()) {
          logger.warn(res1.cause());
          loginFailToWarnValue = Integer.parseInt(MODULE_SPECIFIC_ARGS
            .getOrDefault(LOGIN_ATTEMPTS_TO_WARN_CODE, "3"));
        } else {
          try {
            loginFailToWarnValue = Integer.parseInt(res1.result().getString(VALUE));
          } catch (Exception e) {
            logger.error(e);
          }
        }
        if (loginFailConfigValue != 0) {
          // get time diff between current date and last login attempt
          long diff = new Date().getTime() - attempts.getLastAttempt().getTime();
          // calc date diff in minutes
          long diffMinutes = diff / (60 * 1000) % 60;
          if (diffMinutes > loginTimeoutConfigValue) {
            attempts.setAttemptCount(0);
          } else if (attempts.getAttemptCount() >= loginFailConfigValue && diffMinutes < loginTimeoutConfigValue) {
            result = true;
          }
        }
        if (result) {
            asyncResultHandler.handle(Future.succeededFuture(Authn.PostAuthnLoginResponse.
              respond422WithApplicationJson(
                LoginAPI.getErrors("Fifth failed attempt", LoginAPI.CODE_FIFTH_FAILED_ATTEMPT_BLOCKED))));
        } else {
            asyncResultHandler.handle(Future.succeededFuture(Authn.PostAuthnLoginResponse.
              respond422WithApplicationJson(
                LoginAPI.getErrors("Password does not match",
                  attempts.getAttemptCount().equals(loginFailToWarnValue) ? LoginAPI.CODE_THIRD_FAILED_ATTEMPT : LoginAPI.CODE_P_A_S_S_W_O_R_D_INVALID,
                  new ImmutablePair<>(LoginAPI.PARAM_USERNAME, userObject.getString("username"))
              ))));
        }
        future.complete(result);
      })));
    } catch (Exception e){
      logger.error(e);
      future.complete(false);
    }

    return future;

  }

  /**
   * Load config object by code for login module
   *
   * @param configCode - configuration code
   * @param params     - okapi configuration params
   * @return - json object with configs
   */
  private static Future<JsonObject> getLoginConfig(String configCode, OkapiConnectionParams params) {
    Future<JsonObject> future = Future.future();
    HttpClient client = getHttpClient(params);
    String requestURL;
    String requestToken = params.getToken() != null ? params.getToken() : "";
    try {
      requestURL = params.getOkapiUrl() + "/configurations/entries?query=" +
        "code==" + URLEncoder.encode(configCode, "UTF-8");
    } catch (Exception e) {
      logger.error("Error building request URL: " + e.getLocalizedMessage());
      future.fail(e);
      return future;
    }
    try {
      HttpClientRequest request = client.getAbs(requestURL);
      request.putHeader(OKAPI_TENANT_HEADER, params.getTenantId())
        .putHeader(OKAPI_TOKEN_HEADER, requestToken)
        .putHeader("Content-type", JSON_TYPE)
        .putHeader("Accept", JSON_TYPE);
      request.handler(res -> {
        if (res.statusCode() != 200) {
          res.bodyHandler(buf -> {
            String message = "Expected status code 200, got '" + res.statusCode() +
              "' :" + buf.toString();
            future.fail(message);
          });
        } else {
          res.bodyHandler(buf -> {
            try {
              JsonObject resultObject = buf.toJsonObject();
              if (!resultObject.containsKey("totalRecords") ||
                !resultObject.containsKey("configs")) {
                future.fail("Error, missing field(s) 'totalRecords' and/or 'configs' in config response object");
              } else {
                int recordCount = resultObject.getInteger("totalRecords");
                if (recordCount > 1) {
                  future.fail("Bad results from configs");
                } else if (recordCount == 0) {
                  String errorMessage = "No config found by code " + configCode;
                  logger.error(errorMessage);
                  future.fail(errorMessage);
                } else {
                  future.complete(resultObject.getJsonArray("configs").getJsonObject(0));
                }
              }
            } catch (Exception e) {
              logger.error(e);
              future.fail(e);
            }
          });
        }
      });
      request.setTimeout(params.getTimeout());
      request.exceptionHandler(future::fail);
      request.end();
    } catch (Exception e) {
      String message = "Configs lookup failed: " + e.getLocalizedMessage();
      logger.error(message, e);
      future.fail(message);
    }
    return future;
  }

  /**
   * Prepare HttpClient from OkapiConnection params
   *
   * @param params - Okapi connection params
   * @return - Vertx Http Client
   */
  private static HttpClient getHttpClient(OkapiConnectionParams params) {
    HttpClientOptions options = new HttpClientOptions();
    options.setConnectTimeout(params.getTimeout());
    options.setIdleTimeout(params.getTimeout());
    return params.getVertx().createHttpClient(options);
  }

  /**
   * Method update user entity at mod-user
   *
   * @param user   - Json user object to be updated
   * @param params - object with connection params
   */
  private static Future<Void> updateUser(JsonObject user, OkapiConnectionParams params) {
    Future<Void> future = Future.future();
    HttpClient client = getHttpClient(params);
    String requestURL;
    String requestToken = params.getToken() != null ? params.getToken() : "";
    try {
      requestURL = params.getOkapiUrl() + "/users/" + URLEncoder.encode(
        user.getString("id"), "UTF-8");
    } catch (Exception e) {
      logger.error("Error building request URL: " + e.getLocalizedMessage());
      future.fail(e);
      return future;
    }
    try {
      HttpClientRequest request = client.putAbs(requestURL, res -> {
        if (res.statusCode() != 204) {
          res.bodyHandler(buf -> {
            String message = "Expected status code 204, got '" + res.statusCode() +
              "' :" + buf.toString();
            future.fail(message);
          });
        }else {
          future.complete();
        }
      });
      request.putHeader(OKAPI_TENANT_HEADER, params.getTenantId())
        .putHeader(OKAPI_TOKEN_HEADER, requestToken)
        .putHeader("Content-type", JSON_TYPE)
        .putHeader("accept", "text/plain");
      request.handler(res -> {
        if (res.statusCode() != 204) {
          res.bodyHandler(buf -> {
            String message = "Expected status code 204, got '" + res.statusCode() +
              "' :" + buf.toString();
            future.fail(message);
          });
        }
        future.complete();
      });
      request.setTimeout(params.getTimeout());
      request.exceptionHandler(future::fail);
      request.end(user.encode());
    } catch (Exception e) {
      String message = "User update failed: " + e.getLocalizedMessage();
      logger.error(message, e);
      future.fail(message);
    }
    return future;
  }

  /**
   * Handle on users login fail event
   *
   * @param userObject         - Json user object
   * @param params             - okapi connection params
   * @param pgClient           - postgres client
   * @param asyncResultHandler - request async handler
   */
  public static Handler<AsyncResult<Results<LoginAttempts>>> onLoginFailAttemptHandler(JsonObject userObject,
                                                                        OkapiConnectionParams params,
                                                                        PostgresClient pgClient,
                                                                        Handler<AsyncResult<Response>> asyncResultHandler) {
    return getAttemptReply -> {
      if (getAttemptReply.failed()) {
        logger.debug("Error in PostgresClient get operation " + getAttemptReply.cause().getLocalizedMessage());
        asyncResultHandler.handle(Future.succeededFuture(Authn.PostAuthnLoginResponse
          .respond500WithTextPlain(INTERNAL_ERROR)));
      } else {
        List<LoginAttempts> attempts = getAttemptReply.result().getResults();
        String userId = userObject.getString("id");
        // if there no attempts record for user, create one
        if (attempts.isEmpty()) {
          // save new attempt record to database
          saveAttempt(pgClient, buildLoginAttemptsObject(userId, 1),
            asyncResultHandler, saveAttemptHandler(asyncResultHandler));
          logLoginAttempt(LoginEvent.LOGIN_FAIL, userId, 1);

          asyncResultHandler.handle(Future.succeededFuture(Authn.PostAuthnLoginResponse.
            respond422WithApplicationJson(
              LoginAPI.getErrors("Password does not match", LoginAPI.CODE_P_A_S_S_W_O_R_D_INVALID,
                new ImmutablePair<>(LoginAPI.PARAM_USERNAME, userObject.getString("username"))
              ))));
        } else {
          // check users login attempts
          LoginAttempts attempt = attempts.get(0);
          attempt.setAttemptCount(attempt.getAttemptCount() + 1);
          attempt.setLastAttempt(new Date());
          needToUserBlock(attempt, params, userObject, asyncResultHandler).setHandler(needBlockHandler -> {
            if (needBlockHandler.result()) {
              // lock user account
              JsonObject user = userObject.copy();
              user.put("active", false);
              updateUser(user, params).setHandler(onUpdate -> {
                if (onUpdate.failed()) {
                  String errMsg = "Error on user update: " + onUpdate.cause().getLocalizedMessage();
                  logger.error(errMsg);
                  asyncResultHandler.handle(Future.succeededFuture(Authn.PostAuthnLoginResponse
                    .respond500WithTextPlain(errMsg)));
                }
                attempt.setAttemptCount(0);
                attempt.setLastAttempt(new Date());
                updateAttempt(pgClient, attempt, asyncResultHandler, updateAttemptHandler(asyncResultHandler));
                logLoginAttempt(LoginEvent.LOGIN_FAIL_BLOCK_USER, userId, attempt.getAttemptCount());
              });
            } else {
              Integer attemptCount = attempt.getAttemptCount();
              updateAttempt(pgClient, attempt, asyncResultHandler, updateAttemptHandler(asyncResultHandler));
              logLoginAttempt(LoginEvent.LOGIN_FAIL, userId, attemptCount);
            }
          });
        }
      }
    };
  }

  /**
   * Handle users success login
   *
   * @param userObject         - Json user object
   * @param pgClient           - postgres client
   * @param asyncResultHandler - request async handler
   */
  public static Handler<AsyncResult<Results<LoginAttempts>>> onLoginSuccessAttemptHandler(JsonObject userObject,
                                                                           PostgresClient pgClient,
                                                                           Handler<AsyncResult<Response>> asyncResultHandler) {
    return getAttemptReply -> {
      if (getAttemptReply.failed()) {
        logger.debug("Error in PostgresClient get operation " + getAttemptReply.cause().getLocalizedMessage());
        asyncResultHandler.handle(Future.succeededFuture(Authn.PostAuthnLoginResponse
          .respond500WithTextPlain(INTERNAL_ERROR)));
      } else {
        List<LoginAttempts> attempts = getAttemptReply.result().getResults();
        String userId = userObject.getString("id");
        // if there no attempts record for user, create one
        if (attempts.isEmpty()) {
          // save new attempt record to database
          saveAttempt(pgClient, buildLoginAttemptsObject(userId, 0),
            asyncResultHandler, saveAttemptHandler(asyncResultHandler));
        } else {
          // drops user login attempts
          LoginAttempts attempt = attempts.get(0);
          attempt.setAttemptCount(0);
          attempt.setLastAttempt(new Date());
          updateAttempt(pgClient, attempt, asyncResultHandler, updateAttemptHandler(asyncResultHandler));
        }
        logLoginAttempt(LoginEvent.LOGIN_SUCCESSFUL, userId, 0);
      }
    };
  }

  /**
   * Log login events by default logger
   * TODO - refactor loggin after creating tech design MODLOGIN-36
   * @param event - login event
   * @param userId - user id of logged user
   * @param attempts - failed login attempts number
   */
  private static void logLoginAttempt(LoginEvent event, String userId, Integer attempts) {
    logger.info(event.getCaption() + "UserID: " + userId + "  Date: " + DateFormatUtils.format(new Date(), "yyyy-MM-dd HH:mm:ss") + " Failed login attempts: " + attempts);
  }

  private enum LoginEvent {
    LOGIN_FAIL("LOGIN attempt was FAILED. "),
    LOGIN_SUCCESSFUL("LOGIN attempt was SUCCESSFUL. "),
    LOGIN_FAIL_BLOCK_USER("LOGIN attempt was FAILED. User was BLOCKED. ");

    LoginEvent(String caption) {
      this.caption = caption;
    }

    private String caption;

    public String getCaption() {
      return caption;
    }
  }

}
