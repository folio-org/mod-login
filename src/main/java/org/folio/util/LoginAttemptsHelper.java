package org.folio.util;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.sql.UpdateResult;
import org.folio.rest.RestVerticle;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.jaxrs.model.LogEvent;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.jaxrs.model.Error;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.LoginAttempts;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.interfaces.Results;
import org.folio.services.LogStorageService;

import javax.ws.rs.core.HttpHeaders;
import java.net.URLEncoder;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.rest.impl.LoginAPI.OKAPI_REQUEST_TIMESTAMP_HEADER;
import static org.folio.rest.impl.LoginAPI.CODE_FIFTH_FAILED_ATTEMPT_BLOCKED;
import static org.folio.rest.impl.LoginAPI.OKAPI_TENANT_HEADER;
import static org.folio.rest.impl.LoginAPI.OKAPI_TOKEN_HEADER;
import static org.folio.rest.impl.LoginAPI.X_FORWARDED_FOR_HEADER;
import static org.folio.util.LoginConfigUtils.EVENT_CONFIG_PROXY_STORY_ADDRESS;

/**
 * Helper class that contains static methods which helps with processing Login Attempts business logic
 */
public class LoginAttemptsHelper {

  public static final String LOGIN_ATTEMPTS_SCHEMA_PATH = "ramls/loginAttempts.json";
  public static final String TABLE_NAME_LOGIN_ATTEMPTS = "auth_attempts";
  public static final String LOGIN_ATTEMPTS_CODE = "login.fail.attempts";
  private static final String LOGIN_ATTEMPTS_TO_WARN_CODE = "login.fail.to.warn.attempts";
  public static final String LOGIN_ATTEMPTS_TIMEOUT_CODE = "login.fail.timeout";
  private static final String LOGIN_ATTEMPTS_USERID_FIELD = "'userId'";
  private static final Logger logger = LoggerFactory.getLogger(LoginAttemptsHelper.class);
  private static final String JSON_TYPE = "application/json";
  private static final String VALUE = "value";

  private Vertx vertx;
  private HttpClient httpClient;
  private LogStorageService logStorageService;

  public LoginAttemptsHelper(Vertx vertx) {
    this.vertx = vertx;
    logStorageService = LogStorageService.createProxy(vertx, EVENT_CONFIG_PROXY_STORY_ADDRESS);
    httpClient = vertx.createHttpClient();
  }

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
   * Method save login attempt object to database
   *
   * @param pgClient     - client of postgres database
   * @param loginAttempt - login attempt entity
   */
  private Future<String> saveAttempt(PostgresClient pgClient, LoginAttempts loginAttempt) {
    Future<String> future = Future.future();

    try {
      pgClient.save(TABLE_NAME_LOGIN_ATTEMPTS, loginAttempt.getId(), loginAttempt, future.completer());
    } catch (Exception e) {
      logger.error("Error with postgresclient on saving login attempt during post login request: " + e.getLocalizedMessage());
      future.fail(e.getCause());
    }

    return future;
  }

  /**
   * Method update login attempt object to database
   *
   * @param pgClient     - client of postgres database
   * @param loginAttempt - login attempt entity for update
   */
  private Future<UpdateResult> updateAttempt(PostgresClient pgClient, LoginAttempts loginAttempt) {
    Future<UpdateResult> future = Future.future();

    try {
      pgClient.update(TABLE_NAME_LOGIN_ATTEMPTS, loginAttempt, loginAttempt.getId(), future.completer());
    } catch (Exception e) {
      logger.error(
        "Error with postgresclient on saving login attempt during post login request: "+ e.getLocalizedMessage());
      future.fail(e.getCause());
    }

    return future;
  }

  /**
   * Method get login attempt from database by user id and provide callback for use this data
   *
   * @param userId       - user id for find login attempt entity
   * @param pgClient     - postgres client
   * @return             - login attempts list
   */
  public Future<List<LoginAttempts>> getLoginAttemptsByUserId(String userId, PostgresClient pgClient) {
    Future<Results<LoginAttempts>> future = Future.future();

    try {
      pgClient.get(TABLE_NAME_LOGIN_ATTEMPTS, LoginAttempts.class, buildCriteriaForUserAttempts(userId), false,
        future.completer());
    } catch (Exception e) {
      logger.error("Error with postgres client on getting login attempt during post login request: " + e.getLocalizedMessage());
      future.fail(e.getCause());
    }

    return future.map(Results::getResults);
  }

  /**
   * @param userId - user identifier for his login attempts
   * @param count  - initial count of failed login
   * @return - new Login Attempt object
   */
  private LoginAttempts buildLoginAttemptsObject(String userId, Integer count) {
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
  private Future<Errors> needToUserBlock(LoginAttempts attempts, Map<String, String> okapiHeaders, JsonObject userObject) {
    Future<Errors> future = Future.future();
    try {
      getLoginConfig(LOGIN_ATTEMPTS_CODE, okapiHeaders).setHandler(res ->
        getLoginConfig(LOGIN_ATTEMPTS_TIMEOUT_CODE, okapiHeaders).setHandler(handle ->
          getLoginConfig(LOGIN_ATTEMPTS_TO_WARN_CODE, okapiHeaders).setHandler(res1 -> {
            boolean result = false;
            int loginTimeoutConfigValue = getValue(handle, LOGIN_ATTEMPTS_TIMEOUT_CODE, 10);
            int loginFailConfigValue = getValue(res, LOGIN_ATTEMPTS_CODE, 5);
            int loginFailToWarnValue = getValue(res1, LOGIN_ATTEMPTS_TO_WARN_CODE, 3);

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
            future.complete(defineErrors(result, attempts.getAttemptCount(), userObject.getString("username"), loginFailToWarnValue));
          })));
    } catch (Exception e) {
      logger.error(e);
      future.complete(null);
    }

    return future;

  }

  private int getValue(AsyncResult<JsonObject> res, String key, int defaultValue) {
    if (res.failed()) {
      logger.warn(res.cause());
      return Integer.parseInt(MODULE_SPECIFIC_ARGS
        .getOrDefault(key, String.valueOf(defaultValue)));
    } else try {
      return res.result().getInteger(VALUE);
    } catch (Exception e) {
      logger.error(e);
    }
    return defaultValue;
  }

  private static Errors defineErrors(boolean result, Integer attemptCount, String username, int loginFailToWarnValue) {
    if (result) {
      return LoginAPI.getErrors("Fifth failed attempt", LoginAPI.CODE_FIFTH_FAILED_ATTEMPT_BLOCKED);
    } else {
      return LoginAPI.getErrors("Password does not match",
        attemptCount.equals(loginFailToWarnValue) ? LoginAPI.CODE_THIRD_FAILED_ATTEMPT : LoginAPI.CODE_CREDENTIAL_PW_INCORRECT,
        new ImmutablePair<>(LoginAPI.USERNAME, username));
    }
  }

  /**
   * Load config object by code for login module
   *
   * @param configCode    - configuration code
   * @param okapiHeaders  - okapi headers
   * @return - json object with configs
   */
  private Future<JsonObject> getLoginConfig(String configCode, Map<String, String> okapiHeaders) {
    Future<JsonObject> future = Future.future();
    String requestURL;
    String tenant = okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);
    String requestToken = okapiHeaders.get(RestVerticle.OKAPI_HEADER_TOKEN);
    String okapiUrl = okapiHeaders.get(LoginAPI.OKAPI_URL_HEADER);
    try {
      requestURL = okapiUrl + "/configurations/entries?query=" +
        "code==" + URLEncoder.encode(configCode, "UTF-8");
    } catch (Exception e) {
      logger.error("Error building request URL: " + e.getLocalizedMessage());
      future.fail(e);
      return future;
    }
    try {
      HttpClientRequest request = httpClient.getAbs(requestURL);
      request.putHeader(OKAPI_TENANT_HEADER, tenant)
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
   * Method update user entity at mod-user
   *
   * @param user          - Json user object to be updated
   * @param okapiHeaders  - okapi headers
   */
  private Future<Void> updateUser(JsonObject user, Map<String, String> okapiHeaders) {
    Future<Void> future = Future.future();
    String requestURL;
    String tenant = okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);
    String requestToken = okapiHeaders.get(RestVerticle.OKAPI_HEADER_TOKEN);
    String okapiUrl = okapiHeaders.get(LoginAPI.OKAPI_URL_HEADER);
    try {
      requestURL = okapiUrl + "/users/" + URLEncoder.encode(
        user.getString("id"), "UTF-8");
    } catch (Exception e) {
      logger.error("Error building request URL: " + e.getLocalizedMessage());
      future.fail(e);
      return future;
    }
    try {
      HttpClientRequest request = httpClient.putAbs(requestURL, res -> {
        if (res.statusCode() != 204) {
          res.bodyHandler(buf -> {
            String message = "Expected status code 204, got '" + res.statusCode() +
              "' :" + buf.toString();
            future.fail(message);
          });
        } else {
          future.complete();
        }
      });
      request.putHeader(OKAPI_TENANT_HEADER, tenant)
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
   * @param requestHeaders     - request headers
   * @param attempts           - login attempts list for given user
   */
  public Future<Errors> onLoginFailAttemptHandler(JsonObject userObject, Map<String, String> requestHeaders,
                                                  List<LoginAttempts> attempts) {

    Future<Errors> future = Future.future();

    String userId = userObject.getString("id");
    String tenant = requestHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);

    LogEvent event = new LogEvent();
    event.setEventType(LogEvent.EventType.FAILED_LOGIN_ATTEMPT);
    event.setTenant(tenant);
    event.setUserId(userId);
    event.setBrowserInformation(requestHeaders.get(HttpHeaders.USER_AGENT));
    event.setIp(requestHeaders.get(X_FORWARDED_FOR_HEADER));
    event.setTimestamp(new Date(Long.parseLong(requestHeaders.get(OKAPI_REQUEST_TIMESTAMP_HEADER))));
    logStorageService.logEvent(tenant, JsonObject.mapFrom(requestHeaders), JsonObject.mapFrom(event));

    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenant);
    // if there no attempts record for user, create one
    if (attempts.isEmpty()) {
      // save new attempt record to database
      saveAttempt(pgClient, buildLoginAttemptsObject(userId, 1))
        .map(s -> LoginAPI.getErrors("Password does not match",
          LoginAPI.CODE_CREDENTIAL_PW_INCORRECT,
          new ImmutablePair<>(LoginAPI.USERNAME, userObject.getString(LoginAPI.USERNAME))))
      return future;
    } else {
      // check users login attempts
      LoginAttempts attempt = attempts.get(0);
      attempt.setAttemptCount(attempt.getAttemptCount() + 1);
      attempt.setLastAttempt(new Date());
      return needToUserBlock(attempt, requestHeaders)
        .compose(errors -> {
          if (needBlock(errors)) {
            return blockUser(userObject, requestHeaders, attempt, userId, pgClient)
              .map(v -> errors);
          } else {
            return updateAttempt(pgClient, attempt)
              .map(updateResult -> errors);
          }
        });
    }
  }

  private static boolean needBlock(Errors errors) {
    if (errors != null) {
      List<Error> errorsList = errors.getErrors();
      if (!errorsList.isEmpty() && errorsList.size() == 1) {
        return errorsList.get(0).getCode().equalsIgnoreCase(CODE_FIFTH_FAILED_ATTEMPT_BLOCKED);
      }
    }
    return false;
  }

  private Future<Void> blockUser(JsonObject userObject, Map<String, String> requestHeaders, LoginAttempts attempt,
                                 String userId, PostgresClient pgClient) {

    JsonObject user = userObject.copy();
    user.put("active", false);
    return updateUser(user, requestHeaders)
      .compose(v -> {
        String tenant = requestHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);
        LogEvent logEvent = new LogEvent();
        logEvent.setEventType(LogEvent.EventType.USER_BLOCK);
        logEvent.setTenant(tenant);
        logEvent.setUserId(userId);
        logEvent.setBrowserInformation(requestHeaders.get(HttpHeaders.USER_AGENT));
        logEvent.setIp(requestHeaders.get(X_FORWARDED_FOR_HEADER));
        logEvent.setTimestamp(new Date(Long.parseLong(requestHeaders.get(OKAPI_REQUEST_TIMESTAMP_HEADER))));
        logStorageService.logEvent(tenant, JsonObject.mapFrom(requestHeaders), JsonObject.mapFrom(logEvent));

        attempt.setAttemptCount(0);
        attempt.setLastAttempt(new Date());

        return updateAttempt(pgClient, attempt)
          .map(updateResult -> null);
      });
  }

  /**
   * Handle users success login
   *
   * @param userObject         - Json user object
   * @param requestHeaders     - request headers
   * @param attempts           - login attempts list for given user
   */
  public Future<Void> onLoginSuccessAttemptHandler(JsonObject userObject, Map<String, String> requestHeaders,
                                                   List<LoginAttempts> attempts) {

    String userId = userObject.getString("id");
    String tenant = requestHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);

    LogEvent event = new LogEvent();
    event.setEventType(LogEvent.EventType.SUCCESSFUL_LOGIN_ATTEMPT);
    event.setTenant(tenant);
    event.setUserId(userId);
    event.setBrowserInformation(requestHeaders.get(HttpHeaders.USER_AGENT));
    event.setIp(requestHeaders.get(X_FORWARDED_FOR_HEADER));
    event.setTimestamp(new Date(Long.parseLong(requestHeaders.get(OKAPI_REQUEST_TIMESTAMP_HEADER))));
    logStorageService.logEvent(tenant, JsonObject.mapFrom(requestHeaders), JsonObject.mapFrom(event));

    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenant);
    // if there no attempts record for user, create one
    if (attempts.isEmpty()) {
      // save new attempt record to database
      return saveAttempt(pgClient, buildLoginAttemptsObject(userId, 0))
        .map(s -> null);
    } else {
      // drops user login attempts
      LoginAttempts attempt = attempts.get(0);
      attempt.setAttemptCount(0);
      attempt.setLastAttempt(new Date());
      return updateAttempt(pgClient, attempt)
        .map(updateResult -> null);
    }
  }
}
