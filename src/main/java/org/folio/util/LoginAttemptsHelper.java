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
import org.folio.rest.jaxrs.model.LoginAttempts;
import org.folio.rest.jaxrs.resource.AuthnResource;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.interfaces.Results;

import javax.ws.rs.core.Response;
import java.net.URLEncoder;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.rest.impl.LoginAPI.INTERNAL_ERROR;
import static org.folio.rest.impl.LoginAPI.OKAPI_TENANT_HEADER;
import static org.folio.rest.impl.LoginAPI.OKAPI_TOKEN_HEADER;

/**
 * Helper class that contains static methods which helps with processing Login Attempts business logic
 */
public class LoginAttemptsHelper {

  public static final String LOGIN_ATTEMPTS_SCHEMA_PATH = "apidocs/raml-util/schemas/mod-login/loginAttempts.json";
  public static final String TABLE_NAME_LOGIN_ATTEMPTS = "auth_attempts";
  public static final String LOGIN_ATTEMPTS_CODE = "login.fail.attempts";
  public static final String LOGIN_ATTEMPTS_TIMEOUT_CODE = "login.fail.timeout";
  private static final String LOGIN_ATTEMPTS_USERID_FIELD = "'userId'";
  private static final Logger logger = LoggerFactory.getLogger(LoginAttemptsHelper.class);
  private static final String JSON_TYPE = "application/json";

  /**
   * Method build criteria for lookup Login Attempts for user by user id
   *
   * @param userId - identifier of user
   * @return - Criterion for search login attempts using user id
   */
  public static Criterion buildCriteriaForUserAttempts(String userId) throws Exception {
    Criteria attemptCrit = new Criteria(LOGIN_ATTEMPTS_SCHEMA_PATH);
    attemptCrit.addField(LOGIN_ATTEMPTS_USERID_FIELD);
    attemptCrit.setOperation("=");
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
          AuthnResource.PostAuthnLoginResponse.withPlainInternalServerError(message)));
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
          AuthnResource.PostAuthnLoginResponse.withPlainInternalServerError(message)));
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
      asyncResultHandler.handle(Future.succeededFuture(AuthnResource.PostAuthnLoginResponse
        .withPlainInternalServerError(INTERNAL_ERROR)));
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
      asyncResultHandler.handle(Future.succeededFuture(AuthnResource.PostAuthnLoginResponse
        .withPlainInternalServerError(INTERNAL_ERROR)));
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
                                              Handler<AsyncResult<Results>> replyHandler) {
    try {
      pgClient.get(TABLE_NAME_LOGIN_ATTEMPTS, LoginAttempts.class, buildCriteriaForUserAttempts(userId),
        true, false, replyHandler);
    } catch (Exception e) {
      logger.error("Error with postgres client on getting login attempt during post login request: " + e.getLocalizedMessage());
      asyncResultHandler.handle(Future.succeededFuture(AuthnResource.PostAuthnLoginResponse
        .withPlainInternalServerError(INTERNAL_ERROR)));
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
  private static Future<Boolean> needToUserBlock(LoginAttempts attempts, OkapiConnectionParams params) {
    Future<Boolean> future = Future.future();
    getLoginConfig(LOGIN_ATTEMPTS_CODE, params).setHandler(res -> {
      Integer loginFailConfigValue;
      if (res.failed()) {
        loginFailConfigValue = Integer.parseInt(MODULE_SPECIFIC_ARGS
          .getOrDefault(LOGIN_ATTEMPTS_CODE, "5"));
      } else {
        loginFailConfigValue = res.result().getInteger("value");
      }
      getLoginConfig(LOGIN_ATTEMPTS_TIMEOUT_CODE, params).setHandler(handle -> {
        Integer loginTimeoutConfigValue;
        if (handle.failed()) {
          loginTimeoutConfigValue = Integer.parseInt(MODULE_SPECIFIC_ARGS
            .getOrDefault(LOGIN_ATTEMPTS_TIMEOUT_CODE, "10"));
        } else {
          loginTimeoutConfigValue = handle.result().getInteger("value");
        }
        if (loginFailConfigValue.equals(0)) {
          future.complete(false);
        } else {
          // get time diff between current date and last login attempt
          long diff = new Date().getTime() - attempts.getLastAttempt().getTime();
          // calc date diff in minutes
          long diffMinutes = diff / (60 * 1000) % 60;
          if (diffMinutes > loginTimeoutConfigValue) {
            attempts.setAttemptCount(0);
            future.complete(false);
          } else if (attempts.getAttemptCount() >= loginFailConfigValue && diffMinutes < loginTimeoutConfigValue) {
            future.complete(true);
          } else {
            future.complete(false);
          }
        }
      });
    });
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
      requestURL = params.getOkapiUrl() + "/configurations/entries?query=" + URLEncoder.encode(
        "active==true&module==LOGIN&code==" + configCode, "UTF-8");
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
  public static Handler<AsyncResult<Results>> onLoginFailAttemptHandler(JsonObject userObject,
                                                                        OkapiConnectionParams params,
                                                                        PostgresClient pgClient,
                                                                        Handler<AsyncResult<Response>> asyncResultHandler) {
    return getAttemptReply -> {
      if (getAttemptReply.failed()) {
        logger.debug("Error in PostgresClient get operation " + getAttemptReply.cause().getLocalizedMessage());
        asyncResultHandler.handle(Future.succeededFuture(AuthnResource.PostAuthnLoginResponse
          .withPlainInternalServerError(INTERNAL_ERROR)));
      } else {
        List<LoginAttempts> attempts = (List<LoginAttempts>) getAttemptReply.result().getResults();
        // if there no attempts record for user, create one
        if (attempts.isEmpty()) {
          // save new attempt record to database
          saveAttempt(pgClient, buildLoginAttemptsObject(userObject.getString("id"), 1),
            asyncResultHandler, saveAttemptHandler(asyncResultHandler));
        } else {
          // check users login attempts
          LoginAttempts attempt = attempts.get(0);
          needToUserBlock(attempt, params).setHandler(needBlockHandler -> {
            if (needBlockHandler.result()) {
              // lock user account
              JsonObject user = userObject.copy();
              user.put("active", false);
              updateUser(user, params).setHandler(onUpdate -> {
                if (onUpdate.failed()) {
                  String errMsg = "Error on user update: " + onUpdate.cause().getLocalizedMessage();
                  logger.error(errMsg);
                  asyncResultHandler.handle(Future.succeededFuture(AuthnResource.PostAuthnLoginResponse
                    .withPlainInternalServerError(errMsg)));
                }
                attempt.setAttemptCount(0);
                attempt.setLastAttempt(new Date());
                updateAttempt(pgClient, attempt, asyncResultHandler, updateAttemptHandler(asyncResultHandler));
              });
            } else {
              attempt.setAttemptCount(attempt.getAttemptCount() + 1);
              attempt.setLastAttempt(new Date());
              updateAttempt(pgClient, attempt, asyncResultHandler, updateAttemptHandler(asyncResultHandler));
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
  public static Handler<AsyncResult<Results>> onLoginSuccessAttemptHandler(JsonObject userObject,
                                                                           PostgresClient pgClient,
                                                                           Handler<AsyncResult<Response>> asyncResultHandler) {
    return getAttemptReply -> {
      if (getAttemptReply.failed()) {
        logger.debug("Error in PostgresClient get operation " + getAttemptReply.cause().getLocalizedMessage());
        asyncResultHandler.handle(Future.succeededFuture(AuthnResource.PostAuthnLoginResponse
          .withPlainInternalServerError(INTERNAL_ERROR)));
      } else {
        List<LoginAttempts> attempts = (List<LoginAttempts>) getAttemptReply.result().getResults();
        // if there no attempts record for user, create one
        if (attempts.isEmpty()) {
          // save new attempt record to database
          saveAttempt(pgClient, buildLoginAttemptsObject(userObject.getString("id"), 0),
            asyncResultHandler, saveAttemptHandler(asyncResultHandler));
        } else {
          // drops user login attempts
          LoginAttempts attempt = attempts.get(0);
          attempt.setAttemptCount(0);
          attempt.setLastAttempt(new Date());
          updateAttempt(pgClient, attempt, asyncResultHandler, updateAttemptHandler(asyncResultHandler));
        }
      }
    };
  }

}
