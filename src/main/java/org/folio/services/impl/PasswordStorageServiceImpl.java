package org.folio.services.impl;

import static org.folio.util.LoginConfigUtils.EMPTY_JSON_OBJECT;
import static org.folio.util.LoginConfigUtils.EVENT_CONFIG_PROXY_STORY_ADDRESS;
import static org.folio.util.LoginConfigUtils.SNAPSHOTS_TABLE_CREDENTIALS;
import static org.folio.util.LoginConfigUtils.SNAPSHOTS_TABLE_PW;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;

import org.folio.cql2pgjson.CQL2PgJSON;
import org.folio.cql2pgjson.exception.FieldException;
import org.folio.rest.RestVerticle;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.jaxrs.model.Configurations;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.CredentialsExistence;
import org.folio.rest.jaxrs.model.CredentialsHistory;
import org.folio.rest.jaxrs.model.LogEvent;
import org.folio.rest.jaxrs.model.Metadata;
import org.folio.rest.jaxrs.model.Password;
import org.folio.rest.jaxrs.model.PasswordCreate;
import org.folio.rest.jaxrs.model.PasswordReset;
import org.folio.rest.jaxrs.model.ResponseCreateAction;
import org.folio.rest.jaxrs.model.ResponseResetAction;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.SQLConnection;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Order;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.persist.interfaces.Results;
import org.folio.services.LogStorageService;
import org.folio.services.PasswordStorageService;
import org.folio.util.AuthUtil;
import org.folio.util.WebClientFactory;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;

public class PasswordStorageServiceImpl implements PasswordStorageService {

  private static final String ID_FIELD = "'id'";
  public static final String USER_ID_FIELD = "'userId'";
  private static final String PW_ACTION_ID = "id";
  private static final String ERROR_MESSAGE_STORAGE_SERVICE = "Error while %s | message: %s";
  public static final String TABLE_NAME_CREDENTIALS = "auth_credentials";
  private static final String TABLE_NAME_CREDENTIALS_HISTORY = "auth_credentials_history";
  private static final String CREDENTIALS_HISTORY_DATE_FIELD = "date";
  private static final String PW_HISTORY_NUMBER_CONF_PATH =
    "/configurations/entries?query=configName==password.history.number";//NOSONAR

  public static final int DEFAULT_PASSWORDS_HISTORY_NUMBER = 10;

  private final Logger logger = LoggerFactory.getLogger(PasswordStorageServiceImpl.class);
  private final Vertx vertx;
  private AuthUtil authUtil = new AuthUtil();
  private LogStorageService logStorageService;

  public PasswordStorageServiceImpl(Vertx vertx) {
    this.vertx = vertx;
    logStorageService = LogStorageService.createProxy(vertx, EVENT_CONFIG_PROXY_STORY_ADDRESS);
  }

  @Override
  public PasswordStorageService savePasswordAction(String tenantId, JsonObject passwordEntity,
                                                   Handler<AsyncResult<JsonObject>> asyncHandler) {
    try {
      String id = passwordEntity.getString(PW_ACTION_ID);
      PasswordCreate passwordCreate = passwordEntity.mapTo(PasswordCreate.class);
      PostgresClient pgClient = PostgresClient.getInstance(vertx, tenantId);
      pgClient.startTx(beginTx ->
        pgClient.save(beginTx, SNAPSHOTS_TABLE_PW, id, passwordCreate,
          postReply -> {
            if (postReply.failed()) {
              pgClient.rollbackTx(beginTx,
                rollbackTx ->
                {
                  String errorMessage = String.format(ERROR_MESSAGE_STORAGE_SERVICE,
                    "saving the logging event to the db", postReply.cause().getMessage());
                  logger.error(errorMessage);
                  asyncHandler.handle(Future.failedFuture(errorMessage));
                }
              );
              return;
            }

            String userId = passwordCreate.getUserId();
            Criterion criterion = getCriterionId(userId, USER_ID_FIELD);
            findUserCredentialsByUserId(pgClient, beginTx, criterion, asyncHandler);
          }));
    } catch (Exception ex) {
      String errorMessage = String.format(ERROR_MESSAGE_STORAGE_SERVICE,
        "creating new password entity", ex.getMessage());
      logger.error(errorMessage);
      asyncHandler.handle(Future.failedFuture(errorMessage));
    }
    return this;
  }

  @Override
  public PasswordStorageService getPasswordExistence(String userId, String tenantId, Handler<AsyncResult<JsonObject>> asyncHandler) {
    Criterion criterion = getCriterionId(userId, USER_ID_FIELD);
    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenantId);
    Promise<Results<Credential>> promise = Promise.promise();
    pgClient.get(SNAPSHOTS_TABLE_CREDENTIALS, Credential.class, criterion, true, false, promise);
    promise.future().map(credentialResults -> {
      List<Credential> results = credentialResults.getResults();
      boolean exist = results.size() == 1;
      boolean placeholder = false;
      if (exist) {
        Credential creds = results.get(0);
        if (creds.getHash()
          .equals(authUtil.calculateHash("", creds.getSalt()))) {
          placeholder = true;
        }
      }
      return JsonObject.mapFrom(new CredentialsExistence().withCredentialsExist(exist)
        .withIsPlaceholder(placeholder));
    }).onComplete(asyncHandler);
    return this;
  }

  /**
   * Find user credentials in the `auth_credentials` table by user Id
   */
  private void findUserCredentialsByUserId(PostgresClient pgClient, AsyncResult<SQLConnection> beginTx,
                                           final Criterion criterion, Handler<AsyncResult<JsonObject>> asyncHandler) {
    pgClient.get(beginTx, SNAPSHOTS_TABLE_CREDENTIALS, Credential.class, criterion, true, false,
      getReply -> {
        if (getReply.failed()) {
          pgClient.rollbackTx(beginTx,
            rollbackTx -> asyncHandler.handle(Future.failedFuture(getReply.cause()))
          );
        } else {
          pgClient.endTx(beginTx,
            endTx -> {
              boolean isExist = true;
              Integer totalRecords = getReply.result().getResultInfo().getTotalRecords();
              if (Objects.isNull(totalRecords) || totalRecords <= 0) {
                isExist = false;
              }

              JsonObject response = JsonObject.mapFrom(new ResponseCreateAction().withPasswordExists(isExist));
              asyncHandler.handle(Future.succeededFuture(response));
            });
        }
      });
  }

  @Override
  public PasswordStorageService findPasswordActionById(String tenantId, String actionId,
                                                       Handler<AsyncResult<JsonObject>> asyncHandler) {
    try {
      Criterion criterion = getCriterionId(actionId, ID_FIELD);
      PostgresClient.getInstance(vertx, tenantId)
        .get(SNAPSHOTS_TABLE_PW, PasswordCreate.class, criterion, true, false,
          getReply ->
          {
            if (getReply.failed()) {
              asyncHandler.handle(Future.failedFuture(getReply.cause()));
              return;
            }
            Optional<PasswordCreate> passwordCreateOpt = getReply.result().getResults().stream().findFirst();
            if (!passwordCreateOpt.isPresent()) {
              asyncHandler.handle(Future.succeededFuture(EMPTY_JSON_OBJECT));
              return;
            }

            PasswordCreate passwordCreate = passwordCreateOpt.get();
            asyncHandler.handle(Future.succeededFuture(JsonObject.mapFrom(passwordCreate)));
          });
    } catch (Exception ex) {
      String errorMessage = String.format(ERROR_MESSAGE_STORAGE_SERVICE,
        "find the password entity by id", ex.getMessage());
      logger.error(errorMessage);
      asyncHandler.handle(Future.failedFuture(errorMessage));
    }
    return this;
  }

  @Override
  public PasswordStorageService resetPassword(Map<String, String> requestHeaders, JsonObject resetActionEntity,
                                              Handler<AsyncResult<JsonObject>> asyncHandler) {

    String tenant = requestHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);
    try {
      PasswordReset passwordReset = resetActionEntity.mapTo(PasswordReset.class);
      PostgresClient pgClient = PostgresClient.getInstance(vertx, tenant);

      pgClient.startTx(beginTx ->
        findUserIdByActionId(requestHeaders, beginTx, passwordReset, asyncHandler));
    } catch (Exception ex) {
      String errorMessage = String.format(ERROR_MESSAGE_STORAGE_SERVICE,
        "reset the password action", ex.getMessage());
      logger.error(errorMessage);
      asyncHandler.handle(Future.failedFuture(errorMessage));
    }
    return this;
  }

  /**
   * Find user by id in `auth_password_action` table
   */
  private void findUserIdByActionId(Map<String, String> requestHeaders, AsyncResult<SQLConnection> beginTx,
                                    PasswordReset passwordReset, Handler<AsyncResult<JsonObject>> asyncHandler) {
    String tenant = requestHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);
    String actionId = passwordReset.getPasswordResetActionId();
    Criterion criterionId = getCriterionId(actionId, ID_FIELD);
    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenant);

    pgClient.get(beginTx, SNAPSHOTS_TABLE_PW, PasswordCreate.class, criterionId, true, false,
      reply ->
      {
        if (reply.failed()) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.failedFuture(rollbackTx.cause())));
          return;
        }
        Optional<PasswordCreate> passwordCreateOpt = reply.result().getResults().stream().findFirst();
        if (!passwordCreateOpt.isPresent()) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.succeededFuture(EMPTY_JSON_OBJECT)));
          return;
        }

        String userId = passwordCreateOpt.get().getUserId();
        findUserCredentialById(requestHeaders, beginTx, passwordReset, userId, asyncHandler);
      });
  }

  /**
   * Find user's credential by userId in `auth_credentials` table
   */
  private void findUserCredentialById(Map<String, String> requestHeaders, AsyncResult<SQLConnection> beginTx,
                                      PasswordReset resetAction, String userId,
                                      Handler<AsyncResult<JsonObject>> asyncHandler) {

    String tenant = requestHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);
    String token = requestHeaders.get(RestVerticle.OKAPI_HEADER_TOKEN);
    String okapiUrl = requestHeaders.get(LoginAPI.OKAPI_URL_HEADER);

    Criterion criterion = getCriterionId(userId, USER_ID_FIELD);
    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenant);

    pgClient.get(beginTx, SNAPSHOTS_TABLE_CREDENTIALS, Credential.class, criterion, true, false,
      getReply -> {
        if (getReply.failed()) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.failedFuture(rollbackTx.cause())));
          return;
        }

        String newPassword = resetAction.getNewPassword();
        String actionId = resetAction.getPasswordResetActionId();
        Optional<Credential> credentialOpt = getReply.result().getResults()
          .stream().findFirst();
        if (!credentialOpt.isPresent()) {
          Credential userCredential = createNewCredential(newPassword, userId);
          saveUserCredential(requestHeaders, beginTx, asyncHandler, actionId, userCredential);
        } else {
          Credential userCredential = createCredential(newPassword, credentialOpt.get());
          updateCredAndCredHistory(beginTx, userCredential, tenant, token, okapiUrl)
            .onComplete(v -> {
              deletePasswordActionById(pgClient, beginTx, asyncHandler, actionId, false);

              logStorageService.logEvent(tenant, userId, LogEvent.EventType.PASSWORD_RESET,
                JsonObject.mapFrom(requestHeaders));
            });
        }
      });
  }

  /**
   * Save a new user's credential
   */
  private void saveUserCredential(Map<String, String> requestHeaders, AsyncResult<SQLConnection> beginTx,
                                  Handler<AsyncResult<JsonObject>> asyncHandler, String actionId,
                                  Credential userCredential) {
    String tenant = requestHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);
    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenant);
    String credId = userCredential.getId();
    pgClient.save(beginTx, SNAPSHOTS_TABLE_CREDENTIALS, credId, userCredential,
      putReply -> {
        if (putReply.failed() || !putReply.result().equals(credId)) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.failedFuture(rollbackTx.cause())));
          return;
        }
        deletePasswordActionById(pgClient, beginTx, asyncHandler, actionId, true);
        logStorageService.logEvent(tenant, userCredential.getUserId(),
          LogEvent.EventType.PASSWORD_CREATE, JsonObject.mapFrom(requestHeaders));
      });
  }

  /**
   * Delete the password action by actionId
   */
  private void deletePasswordActionById(PostgresClient pgClient, AsyncResult<SQLConnection> beginTx,
                                        Handler<AsyncResult<JsonObject>> asyncHandler,
                                        String actionId, boolean isNewPassword) {
    pgClient.delete(beginTx, SNAPSHOTS_TABLE_PW, getCriterionId(actionId, ID_FIELD),
      deleteReply -> {
        if (deleteReply.failed()) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.failedFuture(rollbackTx.cause())));
          return;
        }
        int resultCode = deleteReply.result().rowCount();
        if (resultCode <= 0) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.succeededFuture(EMPTY_JSON_OBJECT)));
          return;
        }

        pgClient.endTx(beginTx,
          endTx -> {
            ResponseResetAction response = new ResponseResetAction()
              .withIsNewPassword(isNewPassword);
            asyncHandler.handle(Future.succeededFuture(JsonObject.mapFrom(response)));
          });
      });
  }

  /**
   * Update user credentials
   *
   * @param password a new user's password
   * @param cred     user's credential
   * @return updated user's credential
   */
  private Credential createCredential(String password, Credential cred) {
    String newSalt = authUtil.getSalt();
    String newHash = authUtil.calculateHash(password, newSalt);
    return cred
      .withHash(newHash)
      .withSalt(newSalt);
  }

  /**
   * Create new user credentials
   *
   * @param password a new user's password
   * @param userId   user ID
   * @return new user's credential
   */
  private Credential createNewCredential(String password, String userId) {
    Credential credential = new Credential()
      .withId(UUID.randomUUID().toString())
      .withUserId(userId)
      .withMetadata(new Metadata().withCreatedDate(new Date()));
    return createCredential(password, credential);
  }

  /**
   * Builds criterion wrapper
   *
   * @param actionId id
   * @return Criterion object
   */
  private Criterion getCriterionId(String actionId, String field) {
    Criteria criteria = new Criteria()
      .addField(field)
      .setOperation("=")
      .setVal(actionId);
    return new Criterion(criteria);
  }

  @Override
  public PasswordStorageService updateCredential(JsonObject credJson, Map<String, String> requestHeaders,
                                                 Handler<AsyncResult<Void>> asyncResultHandler) {
    String tenant = requestHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);
    String token = requestHeaders.get(RestVerticle.OKAPI_HEADER_TOKEN);
    String okapiUrl = requestHeaders.get(LoginAPI.OKAPI_URL_HEADER);

    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenant);
    Credential cred = credJson.mapTo(Credential.class);

    pgClient.startTx(handler ->
      updateCredAndCredHistory(handler, cred, tenant, token, okapiUrl)
      .onComplete(res -> {
        if (res.failed()) {
          pgClient.rollbackTx(handler, done -> {
            if (done.failed()) {
              asyncResultHandler.handle(Future.failedFuture(done.cause()));
            } else {
              asyncResultHandler.handle(Future.failedFuture(res.cause()));
            }
          });
        } else {
          pgClient.endTx(handler, done -> {
            if (done.failed()) {
              asyncResultHandler.handle(Future.failedFuture(done.cause()));
            } else {
              logStorageService.logEvent(tenant, cred.getUserId(),
                LogEvent.EventType.PASSWORD_CHANGE, JsonObject.mapFrom(requestHeaders));

              asyncResultHandler.handle(Future.succeededFuture());
            }
          });
        }
      }));

    return this;
  }

  @Override
  public PasswordStorageService isPasswordPreviouslyUsed(JsonObject passwordJson, Map<String, String> okapiHeaders,
                                                         Handler<AsyncResult<Boolean>> asyncResultHandler) {

    Password passwordEntity = passwordJson.mapTo(Password.class);
    String password = passwordEntity.getPassword();
    String userId = passwordEntity.getUserId();

    String tenant = okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);
    String token = okapiHeaders.get(RestVerticle.OKAPI_HEADER_TOKEN);
    String okapiUrl = okapiHeaders.get(LoginAPI.OKAPI_URL_HEADER);

    getCredByUserId(tenant, userId)
      .map(credential ->
        credential.getHash().equals(authUtil.calculateHash(password, credential.getSalt())))
      .compose(used -> {
        if (used) {
          return Future.succeededFuture(Boolean.TRUE);
        } else {
          return getPasswordHistoryNumber(okapiUrl, token, tenant)
            .compose(number -> isPresentInCredHistory(tenant, userId, password, number));
        }
      }).onComplete(used -> {
      if (used.failed()) {
        asyncResultHandler.handle(Future.failedFuture(used.cause()));
        return;
      }
      asyncResultHandler.handle(Future.succeededFuture(used.result()));
    });

    return this;
  }

  protected Future<Credential> getCredByUserId(String tenantId, String userId) {
    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenantId);

    Promise<Credential> promise = Promise.promise();
    Criteria criteria = new Criteria()
      .addField(USER_ID_FIELD)
      .setOperation("=")
      .setVal(userId);

    pgClient.get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(criteria), false, false, get -> {
      if (get.failed()) {
        promise.fail(get.cause());
      } else {
        List<Credential> credList = get.result().getResults();
        if(credList.isEmpty()) {
          promise.fail("No credential found with that userId");
        } else {
          promise.complete(credList.get(0));
        }
      }
    });
    return promise.future();
  }

  private Future<Void> updateCredAndCredHistory(AsyncResult<SQLConnection> conn, Credential cred, String tenant,
                                                String token, String okapiUrl) {

    return getCredByUserId(tenant, cred.getUserId())
      .compose(credential -> updateCred(conn, tenant, cred.withId(credential.getId()), credential))
      .compose(credential -> updateCredHistory(conn, credential, okapiUrl, token, tenant));
  }

  private Future<Credential> updateCred(AsyncResult<SQLConnection> conn, String tenantId,
                                        Credential newCred, Credential oldCred) {
    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenantId);
    Promise<RowSet<Row>> promise = Promise.promise();
    CQL2PgJSON field = null;
    try {
      field = new CQL2PgJSON(TABLE_NAME_CREDENTIALS + ".jsonb");
    } catch (FieldException e) {
      promise.fail(e);
    }
    CQLWrapper cqlWrapper = new CQLWrapper(field, "id==" + newCred.getId());
    pgClient.update(conn, TABLE_NAME_CREDENTIALS, newCred, cqlWrapper, true, promise);
    return promise.future().map(updated -> oldCred);
  }

  private Future<Void> updateCredHistory(AsyncResult<SQLConnection> conn, Credential cred,
                                         String okapiUrl, String token, String tenant) {

    return getCredHistoryCountByUserId(tenant, cred.getUserId())
      .compose(count -> getPasswordHistoryNumber(okapiUrl, token, tenant)
        .map(number -> count - number + 2))
      .compose(count -> deleteOldCredHistoryRecords(tenant, cred.getUserId(), count))
      .compose(v -> {
        Promise<String> promise = Promise.promise();
        CredentialsHistory credHistory = new CredentialsHistory();
        credHistory.setId(UUID.randomUUID().toString());
        credHistory.setUserId(cred.getUserId());
        credHistory.setSalt(cred.getSalt());
        credHistory.setHash(cred.getHash());
        credHistory.setDate(new Date());

        PostgresClient pgClient = PostgresClient.getInstance(vertx, tenant);
        pgClient.save(conn, TABLE_NAME_CREDENTIALS_HISTORY, UUID.randomUUID().toString(), credHistory, promise);
        return promise.future().map(s -> null);
      });
  }

  private Future<Integer> getCredHistoryCountByUserId(String tenantId, String userId) {
    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenantId);
    String tableName = String.format(
      "%s.%s", PostgresClient.convertToPsqlStandard(tenantId), TABLE_NAME_CREDENTIALS_HISTORY);
    Promise<RowSet<Row>> promise = Promise.promise();
    String query = String.format("SELECT count(id) FROM %s WHERE jsonb->>'userId' = '%s'", tableName, userId);
    pgClient.select(query, promise);
    return promise.future().map(resultSet -> resultSet.iterator().next().getInteger(0));
  }

  private Future<Void> deleteOldCredHistoryRecords(String tenantId, String userId, Integer count) {
    if (count < 1) {
      return Future.succeededFuture();
    }

    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenantId);
    Promise<RowSet<Row>> promise = Promise.promise();
    String tableName = String.format(
      "%s.%s", PostgresClient.convertToPsqlStandard(tenantId), TABLE_NAME_CREDENTIALS_HISTORY);

    String query = String.format("DELETE FROM %s WHERE id IN " +
        "(SELECT id FROM %s WHERE jsonb->>'userId' = '%s' ORDER BY jsonb->>'date' ASC LIMIT %d)",
      tableName, tableName, userId, count);
    pgClient.execute(query, promise);
    return promise.future().map(s -> null);
  }

  private Future<Boolean> isPresentInCredHistory(String tenantId, String userId,
                                                 String password, int pwdHistoryNumber) {
    Promise<Boolean> promise = Promise.promise();
    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenantId);

    Criteria criteria = new Criteria()
      .addField(USER_ID_FIELD)
      .setOperation("=")
      .setVal(userId);

    Criterion criterion = new Criterion(criteria)
      .setOrder(new Order(String.format("jsonb->>'%s'", CREDENTIALS_HISTORY_DATE_FIELD), Order.ORDER.DESC))
      .setLimit(new Limit(pwdHistoryNumber - 1));

    pgClient.get(TABLE_NAME_CREDENTIALS_HISTORY, CredentialsHistory.class, criterion, true, get -> {
      if (get.failed()) {
        promise.fail(get.cause());
        return;
      }

      boolean anyMatch = get.result().getResults().stream()
        .map(history -> authUtil.calculateHash(password, history.getSalt()))
        .anyMatch(hash -> get.result().getResults().stream()
          .anyMatch(history -> history.getHash().equals(hash)));

      promise.complete(anyMatch);
    });

    return promise.future();
  }

  private Future<Integer> getPasswordHistoryNumber(String okapiUrl, String token, String tenant) {
    Promise<Integer> promise = Promise.promise();

    WebClientFactory.getWebClient(vertx).getAbs(okapiUrl + PW_HISTORY_NUMBER_CONF_PATH)
      .putHeader(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
      .putHeader(RestVerticle.OKAPI_HEADER_TOKEN, token)
      .putHeader(RestVerticle.OKAPI_HEADER_TENANT, tenant)
      .send(ar -> {
        if(ar.failed()) {
          promise.complete(DEFAULT_PASSWORDS_HISTORY_NUMBER);
        } else {
          HttpResponse<Buffer> resp = ar.result();
          if (resp.statusCode() != 200) {
            promise.complete(DEFAULT_PASSWORDS_HISTORY_NUMBER);
          } else {
            Configurations conf = resp.bodyAsJson(Configurations.class);
            if (conf.getConfigs().isEmpty()) {
              promise.complete(DEFAULT_PASSWORDS_HISTORY_NUMBER);
              return;
            }
            promise.complete(Integer.valueOf(conf.getConfigs().get(0).getValue()));
          }
        }
      });

    return promise.future();
  }
}
