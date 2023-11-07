package org.folio.util;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.rest.impl.LoginAPI.CODE_FIFTH_FAILED_ATTEMPT_BLOCKED;
import static org.folio.util.LoginConfigUtils.EVENT_CONFIG_PROXY_STORY_ADDRESS;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.jaxrs.model.Error;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.LogEvent;
import org.folio.rest.jaxrs.model.LoginAttempts;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.services.LogStorageService;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;

/**
 * Helper class that contains static methods which helps with processing Login Attempts business logic
 */
public class LoginAttemptsHelper {

  public static final String TABLE_NAME_LOGIN_ATTEMPTS = "auth_attempts";
  public static final String LOGIN_ATTEMPTS_CODE = "login.fail.attempts";
  public static final String LOGIN_ATTEMPTS_TO_WARN_CODE = "login.fail.to.warn.attempts";
  public static final String LOGIN_ATTEMPTS_TIMEOUT_CODE = "login.fail.timeout";
  private static final String LOGIN_ATTEMPTS_USERID_FIELD = "'userId'";
  public static final Logger logger = LogManager.getLogger(LoginAttemptsHelper.class);
  private static final String VALUE = "value";

  private Vertx vertx;
  private LogStorageService logStorageService;

  public LoginAttemptsHelper(Vertx vertx) {
    this.vertx = vertx;
    logStorageService = LogStorageService.createProxy(vertx, EVENT_CONFIG_PROXY_STORY_ADDRESS);
  }

  /**
   * Method build criteria for lookup Login Attempts for user by user id
   *
   * @param userId - identifier of user
   * @return - Criterion for search login attempts using user id
   */
  public static Criterion buildCriteriaForUserAttempts(String userId) {
    Criteria attemptCrit = new Criteria();
    attemptCrit.addField(LOGIN_ATTEMPTS_USERID_FIELD);
    attemptCrit.setOperation("=");
    attemptCrit.setVal(userId);
    return new Criterion(attemptCrit);
  }


  /**
   * Method save login attempt object to database
   *
   * @param pgClient     - client of postgres database
   * @param loginAttempt - login attempt entity
   */
  private Future<Void> saveAttempt(PostgresClient pgClient, LoginAttempts loginAttempt) {
    Promise<Void> promise = Promise.promise();
    pgClient.save(TABLE_NAME_LOGIN_ATTEMPTS, loginAttempt.getId(), loginAttempt, reply -> promise.complete());
    return promise.future();
  }

  /**
   * Method update login attempt object to database
   *
   * @param pgClient     - client of postgres database
   * @param loginAttempt - login attempt entity for update
   */
  private Future<Void> updateAttempt(PostgresClient pgClient, LoginAttempts loginAttempt) {
    Promise<Void> promise = Promise.promise();
    pgClient.update(TABLE_NAME_LOGIN_ATTEMPTS, loginAttempt, loginAttempt.getId(), done -> promise.complete());
    return promise.future();
  }

  /**
   * Method get login attempt from database by user id and provide callback for use this data
   *
   * @param userId       - user id for find login attempt entity
   * @param pgClient     - postgres client
   * @return             - login attempts list
   */
  public Future<List<LoginAttempts>> getLoginAttemptsByUserId(String userId, PostgresClient pgClient) {
    Promise<List<LoginAttempts>> promise = Promise.promise();
    pgClient.get(TABLE_NAME_LOGIN_ATTEMPTS, LoginAttempts.class, buildCriteriaForUserAttempts(userId), false,
        reply -> promise.complete(reply.result().getResults()));
    return promise.future();
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
    Promise<Errors> promise = Promise.promise();
    try {
      Future<JsonObject> attemptsFut = getLoginConfig(LOGIN_ATTEMPTS_CODE, okapiHeaders);
      Future<JsonObject> timeoutFut = getLoginConfig(LOGIN_ATTEMPTS_TIMEOUT_CODE, okapiHeaders);
      Future<JsonObject> warnFut = getLoginConfig(LOGIN_ATTEMPTS_TO_WARN_CODE, okapiHeaders);

      attemptsFut.onComplete(attemptsConf ->
        timeoutFut.onComplete(timeoutConf ->
          warnFut.onComplete(warnConf -> {
            boolean result = false;
            int loginTimeoutConfigValue = getValue(timeoutConf, LOGIN_ATTEMPTS_TIMEOUT_CODE, 10);
            int loginFailConfigValue = getValue(attemptsConf, LOGIN_ATTEMPTS_CODE, 5);
            int loginFailToWarnValue = getValue(warnConf, LOGIN_ATTEMPTS_TO_WARN_CODE, 3);

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
            promise.complete(defineErrors(result, attempts.getAttemptCount(), userObject.getString("username"), loginFailToWarnValue));
          })));
    } catch (Exception e) {
      logger.error(e);
      promise.complete(null);
    }
    return promise.future();
  }

  private int getValue(AsyncResult<JsonObject> res, String key, int defaultValue) {
    defaultValue = Integer.parseInt(MODULE_SPECIFIC_ARGS.getOrDefault(key, String.valueOf(defaultValue)));
    if (res.failed()) {
      logger.warn(res.cause());
    } else try {
      return Integer.parseInt(res.result().getValue(VALUE, defaultValue).toString());
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
    String requestURL;
    String tenant = okapiHeaders.get(XOkapiHeaders.TENANT);
    String requestToken = okapiHeaders.get(XOkapiHeaders.TOKEN);
    String okapiUrl = okapiHeaders.get(XOkapiHeaders.URL);

    requestURL = okapiUrl + "/configurations/entries?query=" + PercentCodec.encode("code==" + configCode);
    HttpRequest<Buffer> request = WebClientFactory.getWebClient(vertx).getAbs(requestURL);
    request.putHeader(XOkapiHeaders.TENANT, tenant)
      .putHeader(XOkapiHeaders.TOKEN, requestToken);
    return request
      .expect(ResponsePredicate.JSON)
      .expect(ResponsePredicate.SC_OK)
      .send().map(res -> {
      JsonObject resultObject = res.bodyAsJsonObject();
      JsonArray configs = resultObject.getJsonArray("configs");
      return configs.isEmpty() ? new JsonObject() : configs.getJsonObject(0);
    });
  }

  /**
   * Method update user entity at mod-user
   *
   * @param user          - Json user object to be updated
   * @param okapiHeaders  - okapi headers
   */
  private Future<Void> updateUser(JsonObject user, Map<String, String> okapiHeaders) {
    String tenant = okapiHeaders.get(XOkapiHeaders.TENANT);
    String requestToken = okapiHeaders.get(XOkapiHeaders.TOKEN);
    String okapiUrl = okapiHeaders.get(XOkapiHeaders.URL);
    String requestURL = okapiUrl + "/users/" + StringUtil.urlEncode(user.getString("id"));
    HttpRequest<Buffer> request = WebClientFactory.getWebClient(vertx).putAbs(requestURL);
    request.putHeader(XOkapiHeaders.TENANT, tenant)
      .putHeader(XOkapiHeaders.TOKEN, requestToken);
    return request
      .expect(ResponsePredicate.SC_NO_CONTENT)
      .sendJsonObject(user).mapEmpty();
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

    String userId = userObject.getString("id");
    String tenant = requestHeaders.get(XOkapiHeaders.TENANT);

    logStorageService.logEvent(tenant, userId, LogEvent.EventType.FAILED_LOGIN_ATTEMPT,
        LoginConfigUtils.encodeJsonHeaders(requestHeaders));

    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenant);
    // if there no attempts record for user, create one
    if (attempts.isEmpty()) {
      // save new attempt record to database
      return saveAttempt(pgClient, buildLoginAttemptsObject(userId, 1))
        .map(s -> LoginAPI.getErrors("Password does not match",
          LoginAPI.CODE_CREDENTIAL_PW_INCORRECT,
          new ImmutablePair<>(LoginAPI.USERNAME, userObject.getString(LoginAPI.USERNAME))));
    } else {
      // check users login attempts
      LoginAttempts attempt = attempts.get(0);
      attempt.setAttemptCount(attempt.getAttemptCount() + 1);
      attempt.setLastAttempt(new Date());
      return needToUserBlock(attempt, requestHeaders, userObject)
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
        String tenant = requestHeaders.get(XOkapiHeaders.TENANT);
        logStorageService.logEvent(tenant, userId, LogEvent.EventType.USER_BLOCK,
            LoginConfigUtils.encodeJsonHeaders(requestHeaders));

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
    String tenant = requestHeaders.get(XOkapiHeaders.TENANT);

    logStorageService.logEvent(tenant, userId, LogEvent.EventType.SUCCESSFUL_LOGIN_ATTEMPT,
        LoginConfigUtils.encodeJsonHeaders(requestHeaders));

    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenant);
    // if there no attempts record for user, create one
    if (attempts.isEmpty()) {
      // save new attempt record to database
      return saveAttempt(pgClient, buildLoginAttemptsObject(userId, 0));
    } else {
      // drops user login attempts
      LoginAttempts attempt = attempts.get(0);
      attempt.setAttemptCount(0);
      attempt.setLastAttempt(new Date());
      return updateAttempt(pgClient, attempt);
    }
  }
}
