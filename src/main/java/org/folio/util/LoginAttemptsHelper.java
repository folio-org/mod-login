package org.folio.util;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.util.LoginConfigUtils.EVENT_CONFIG_PROXY_STORY_ADDRESS;

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import io.vertx.sqlclient.Tuple;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.XOkapiHeaders;
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
  private Future<Boolean> needToUserBlock(LoginAttempts attempts, Map<String, String> okapiHeaders) {
    try {
      Future<JsonObject> attemptsFut = getLoginConfig(LOGIN_ATTEMPTS_CODE, okapiHeaders);
      Future<JsonObject> timeoutFut = getLoginConfig(LOGIN_ATTEMPTS_TIMEOUT_CODE, okapiHeaders);

      return Future.join(attemptsFut, timeoutFut)
          .map(compositeFuture -> {
            boolean result = false;
            int loginTimeoutConfigValue = getValue(timeoutFut, LOGIN_ATTEMPTS_TIMEOUT_CODE, 10);
            int loginFailConfigValue = getValue(attemptsFut, LOGIN_ATTEMPTS_CODE, 5);

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
            return result;
          });
    } catch (Exception e) {
      logger.error("{}", e.getMessage(), e);
      return Future.failedFuture(e);
    }
  }

  static int getValue(AsyncResult<JsonObject> res, String key, int defaultValue) {
    var arg = MODULE_SPECIFIC_ARGS.get(key);
    try {
      if (arg != null) {
        defaultValue = Integer.parseInt(arg);
      }
    } catch (Exception e) {
      logger.error("Expected integer but the value for module argument '{}' is '{}'", key, arg, e);
    }

    if (res.failed()) {
      logger.warn(res.cause().getMessage(), res.cause());
      return defaultValue;
    }

    var result = res.result().getString(VALUE);
    try {
      if (result != null) {
        return Integer.parseInt(result);
      }
    } catch (Exception e) {
      logger.error("Expected integer but the value for configuration argument is '{}'", result, e);
    }

    return defaultValue;
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
  public void onLoginFailAttemptHandler(JsonObject userObject, Map<String, String> requestHeaders) {
    var userId = userObject.getString("id");
    var tenant = requestHeaders.get(XOkapiHeaders.TENANT);
    AtomicReference<LoginAttempts> loginAttempts = new AtomicReference<>(buildLoginAttemptsObject(userId, 1));
    var id = loginAttempts.get().getId();
    var jsonb = JsonObject.mapFrom(loginAttempts.get());
    var pgClient = PostgresClient.getInstance(vertx, tenant);

    // atomic upsert to prevent any race condition,
    // there were sporadic failures in EventsLoggingTest.testUserBlock
    pgClient.execute("""
                     INSERT INTO auth_attempts (id, jsonb) VALUES ($1, $2)
                       ON CONFLICT (lower(f_unaccent(jsonb ->> 'userId')))
                       DO UPDATE set jsonb=jsonb_set(jsonb_set(auth_attempts.jsonb,
                         '{attemptCount}', to_jsonb((auth_attempts.jsonb->'attemptCount')::bigint+1)),
                         '{lastAttempt}', excluded.jsonb->'lastAttempt')
                       RETURNING jsonb::text
                     """, Tuple.of(id, jsonb))
    .compose(rowSet -> {
      var newJsonb = rowSet.iterator().next().getString(0);
      loginAttempts.set(Json.decodeValue(newJsonb, LoginAttempts.class));
      return needToUserBlock(loginAttempts.get(), requestHeaders);
    })
    .onSuccess(needBlock -> {
      if (Boolean.TRUE.equals(needBlock)) {
        blockUser(userObject, requestHeaders, loginAttempts.get(), userId, pgClient);
      }
    })
    .onFailure(e -> logger.error("{}", e.getMessage(), e));

    logStorageService.logEvent(tenant, userId, LogEvent.EventType.FAILED_LOGIN_ATTEMPT,
        LoginConfigUtils.encodeJsonHeaders(requestHeaders));
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
  public Future<Void> onLoginSuccessAttemptHandler(JsonObject userObject, Map<String, String> requestHeaders) {

    var userId = userObject.getString("id");
    var tenant = requestHeaders.get(XOkapiHeaders.TENANT);
    var loginAttempts = buildLoginAttemptsObject(userId, 0);
    var id = loginAttempts.getId();
    var jsonb = JsonObject.mapFrom(loginAttempts);
    // atomic upsert to prevent any race condition
    var future = PostgresClient.getInstance(vertx, tenant)
        .execute("""
                 INSERT INTO auth_attempts (id, jsonb) VALUES ($1, $2)
                   ON CONFLICT (lower(f_unaccent(jsonb ->> 'userId')))
                   DO UPDATE set jsonb=excluded.jsonb;
                 """, Tuple.of(id, jsonb))
        .<Void>mapEmpty();

    logStorageService.logEvent(tenant, userId, LogEvent.EventType.SUCCESSFUL_LOGIN_ATTEMPT,
        LoginConfigUtils.encodeJsonHeaders(requestHeaders));

    return future;
  }
}
