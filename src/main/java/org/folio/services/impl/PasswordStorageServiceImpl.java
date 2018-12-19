package org.folio.services.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;
import org.apache.http.HttpStatus;
import org.folio.rest.RestVerticle;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.jaxrs.model.Configurations;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.CredentialsExistence;
import org.folio.rest.jaxrs.model.CredentialsHistory;
import org.folio.rest.jaxrs.model.Metadata;
import org.folio.rest.jaxrs.model.Password;
import org.folio.rest.jaxrs.model.PasswordCreate;
import org.folio.rest.jaxrs.model.PasswordReset;
import org.folio.rest.jaxrs.model.ResponseCreateAction;
import org.folio.rest.jaxrs.model.ResponseResetAction;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Order;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.persist.interfaces.Results;
import org.folio.services.PasswordStorageService;
import org.folio.util.AuthUtil;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;
import org.z3950.zing.cql.cql2pgjson.FieldException;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.folio.rest.persist.Criteria.Criteria.OP_EQUAL;
import static org.folio.util.LoginConfigUtils.EMPTY_JSON_OBJECT;
import static org.folio.util.LoginConfigUtils.SNAPSHOTS_TABLE_CREDENTIALS;
import static org.folio.util.LoginConfigUtils.SNAPSHOTS_TABLE_PW;

public class PasswordStorageServiceImpl implements PasswordStorageService {

  private static final String ID_FIELD = "'id'";
  private static final String USER_ID_FIELD = "'userId'";
  private static final String PW_ACTION_ID = "id";
  private static final String ERROR_MESSAGE_STORAGE_SERVICE = "Error while %s | message: %s";
  private static final String TABLE_NAME_CREDENTIALS = "auth_credentials";
  private static final String TABLE_NAME_CREDENTIALS_HISTORY = "auth_credentials_history";
  private static final String CREDENTIALS_HISTORY_DATE_FIELD = "date";
  private static final String PW_HISTORY_NUMBER_CONF_PATH =
    "/configurations/entries?query=configName==password.history.number";//NOSONAR

  public static final int DEFAULT_PASSWORDS_HISTORY_NUMBER = 10;

  private final Logger logger = LoggerFactory.getLogger(PasswordStorageServiceImpl.class);
  private final Vertx vertx;
  private AuthUtil authUtil = new AuthUtil();

  public PasswordStorageServiceImpl(Vertx vertx) {
    this.vertx = vertx;
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
    Future<Results<Credential>> future = Future.future();
    pgClient.get(SNAPSHOTS_TABLE_CREDENTIALS, Credential.class, criterion, true, false, future.completer());
    future.map(credentialResults -> {
      boolean credentialFound = credentialResults.getResultInfo().getTotalRecords() == 1;
      return JsonObject.mapFrom(new CredentialsExistence().withCredentialsExist(credentialFound));
    }).setHandler(asyncHandler);
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
  public PasswordStorageService resetPassword(Map<String, String> okapiHeaders, JsonObject resetActionEntity,
                                              Handler<AsyncResult<JsonObject>> asyncHandler) {

    String tenant = okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);
    String token = okapiHeaders.get(RestVerticle.OKAPI_HEADER_TOKEN);
    String okapiUrl = okapiHeaders.get(LoginAPI.OKAPI_URL_HEADER);

    try {
      PasswordReset passwordReset = resetActionEntity.mapTo(PasswordReset.class);
      PostgresClient pgClient = PostgresClient.getInstance(vertx, tenant);

      pgClient.startTx(beginTx ->
        findUserIdByActionId(tenant, token, okapiUrl, beginTx, passwordReset, asyncHandler));
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
  private void findUserIdByActionId(String tenant, String token, String okapiUrl,
                                    AsyncResult<SQLConnection> beginTx, PasswordReset passwordReset,
                                    Handler<AsyncResult<JsonObject>> asyncHandler) {
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
        findUserCredentialById(tenant, token, okapiUrl, beginTx, passwordReset, userId, asyncHandler);
      });
  }

  /**
   * Find user's credential by userId in `auth_credentials` table
   */
  private void findUserCredentialById(String tenant, String token, String okapiUrl,
                                      AsyncResult<SQLConnection> beginTx, PasswordReset resetAction,
                                      String userId, Handler<AsyncResult<JsonObject>> asyncHandler) {

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
          saveUserCredential(pgClient, beginTx, asyncHandler, actionId, userCredential);
        } else {
          Credential userCredential = createCredential(newPassword, credentialOpt.get());
          updateCredAndCredHistory(beginTx, userCredential, tenant, token, okapiUrl)
            .setHandler(v -> deletePasswordActionById(pgClient, beginTx, asyncHandler, actionId, false));
        }
      });
  }

  /**
   * Save a new user's credential
   */
  private void saveUserCredential(PostgresClient pgClient, AsyncResult<SQLConnection> beginTx,
                                  Handler<AsyncResult<JsonObject>> asyncHandler, String actionId,
                                  Credential userCredential) {
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
        int resultCode = deleteReply.result().getUpdated();
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
      .setOperation(Criteria.OP_EQUAL)
      .setValue(actionId);
    return new Criterion(criteria);
  }

  @Override
  public PasswordStorageService updateCredential(JsonObject credJson, Map<String, String> okapiHeaders,
                                                 Handler<AsyncResult<Void>> asyncResultHandler) {
    String tenant = okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);
    String token = okapiHeaders.get(RestVerticle.OKAPI_HEADER_TOKEN);
    String okapiUrl = okapiHeaders.get(LoginAPI.OKAPI_URL_HEADER);

    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenant);
    Credential cred = credJson.mapTo(Credential.class);

    pgClient.startTx(conn -> updateCredAndCredHistory(conn, cred, tenant, token, okapiUrl)
      .setHandler(res -> {
        if (res.failed()) {
          conn.result().rollback(v -> {
            conn.result().close();
            if (v.failed()) {
              asyncResultHandler.handle(Future.failedFuture(v.cause()));
            } else {
              asyncResultHandler.handle(Future.failedFuture(res.cause()));
            }
          });
        } else {
          conn.result().commit(v -> {
            conn.result().close();
            if (v.failed()) {
              asyncResultHandler.handle(Future.failedFuture(v.cause()));
            } else {
              asyncResultHandler.handle(Future.succeededFuture());
            }
          });
        }
      }));

    return this;
  }

  @Override
  public PasswordStorageService isPasswordPreviouslyUsed(JsonObject passwordEntity, Map<String, String> okapiHeaders,
                                                         Handler<AsyncResult<Boolean>> asyncResultHandler) {

    Password password = passwordEntity.mapTo(Password.class);
    String tenant = okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);
    String token = okapiHeaders.get(RestVerticle.OKAPI_HEADER_TOKEN);
    String userId = okapiHeaders.get(LoginAPI.OKAPI_USER_ID_HEADER);
    String okapiUrl = okapiHeaders.get(LoginAPI.OKAPI_URL_HEADER);

    getCredByUserId(tenant, userId)
      .map(credential ->
        credential.getHash().equals(authUtil.calculateHash(password.getPassword(), credential.getSalt())))
      .compose(used -> {
        if (used) {
          return Future.succeededFuture(Boolean.TRUE);
        } else {
          return getPasswordHistoryNumber(okapiUrl, token, tenant)
            .compose(number -> isPresentInCredHistory(tenant, userId, password, number));
        }
      }).setHandler(used -> {
      if (used.failed()) {
        asyncResultHandler.handle(Future.failedFuture(used.cause()));
        return;
      }
      asyncResultHandler.handle(Future.succeededFuture(used.result()));
    });

    return this;
  }

  private Future<Credential> getCredByUserId(String tenantId, String userId) {
    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenantId);

    Future<Credential> future = Future.future();
    Criteria criteria = new Criteria()
      .addField(USER_ID_FIELD)
      .setOperation(Criteria.OP_EQUAL)
      .setValue(userId);

    pgClient.get(TABLE_NAME_CREDENTIALS, Credential.class, new Criterion(criteria), false, false, get -> {
      if (get.failed()) {
        future.fail(get.cause());
      } else {
        List<Credential> credList = get.result().getResults();
        if(credList.isEmpty()) {
          future.fail("No credential found with that userId");
        } else {
          future.complete(credList.get(0));
        }
      }
    });
    return future;
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
    Future<UpdateResult> future = Future.future();
    CQL2PgJSON field = null;
    try {
      field = new CQL2PgJSON(TABLE_NAME_CREDENTIALS + ".jsonb");
    } catch (FieldException e) {
      future.fail(e);
    }
    CQLWrapper cqlWrapper = new CQLWrapper(field, "id==" + newCred.getId());
    pgClient.update(conn, TABLE_NAME_CREDENTIALS, newCred, cqlWrapper, true, future.completer());

    return future.map(updateResult -> oldCred);
  }

  private Future<Void> updateCredHistory(AsyncResult<SQLConnection> conn, Credential cred,
                                         String okapiUrl, String token, String tenant) {

    return getCredHistoryCountByUserId(tenant, cred.getUserId())
      .compose(count -> getPasswordHistoryNumber(okapiUrl, token, tenant)
        .map(number -> count - number + 2))
      .compose(count -> deleteOldCredHistoryRecords(conn, tenant, cred.getUserId(), count))
      .compose(v -> {
        Future<String> future = Future.future();
        CredentialsHistory credHistory = new CredentialsHistory();
        credHistory.setId(UUID.randomUUID().toString());
        credHistory.setUserId(cred.getUserId());
        credHistory.setSalt(cred.getSalt());
        credHistory.setHash(cred.getHash());
        credHistory.setDate(new Date());

        PostgresClient pgClient = PostgresClient.getInstance(vertx, tenant);
        pgClient.save(conn, TABLE_NAME_CREDENTIALS_HISTORY, UUID.randomUUID().toString(),
          credHistory, future.completer());

        return future.map(s -> null);
      });
  }

  private Future<Integer> getCredHistoryCountByUserId(String tenantId, String userId) {
    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenantId);
    String tableName = String.format(
      "%s.%s", PostgresClient.convertToPsqlStandard(tenantId), TABLE_NAME_CREDENTIALS_HISTORY);
    Future<ResultSet> future = Future.future();
    String query = String.format("SELECT count(_id) FROM %s WHERE jsonb->>'userId' = '%s'", tableName, userId);
    pgClient.select(query, future.completer());

    return future.map(resultSet -> resultSet.getResults().get(0).getInteger(0));
  }

  private Future<Void> deleteOldCredHistoryRecords(AsyncResult<SQLConnection> conn, String tenantId,
                                                   String userId, Integer count) {
    if (count < 1) {
      return Future.succeededFuture();
    }

    Future<Void> future = Future.future();
    String tableName = String.format(
      "%s.%s", PostgresClient.convertToPsqlStandard(tenantId), TABLE_NAME_CREDENTIALS_HISTORY);

    String query = String.format("DELETE FROM %s WHERE _id IN " +
        "(SELECT _id FROM %s WHERE jsonb->>'userId' = '%s' ORDER BY jsonb->>'date' ASC LIMIT %d)",
      tableName, tableName, userId, count);
    conn.result().execute(query, future.completer());

    return future;
  }

  private Future<Boolean> isPresentInCredHistory(String tenantId, String userId,
                                                 Password password, int pwdHistoryNumber) {
    Future<Boolean> future = Future.future();
    PostgresClient pgClient = PostgresClient.getInstance(vertx, tenantId);

    Criteria criteria = new Criteria()
      .addField(USER_ID_FIELD)
      .setOperation(OP_EQUAL)
      .setValue(userId);

    Criterion criterion = new Criterion(criteria)
      .setOrder(new Order(String.format("jsonb->>'%s'", CREDENTIALS_HISTORY_DATE_FIELD), Order.ORDER.DESC))
      .setLimit(new Limit(pwdHistoryNumber - 1));

    pgClient.get(TABLE_NAME_CREDENTIALS_HISTORY, CredentialsHistory.class, criterion, true, get -> {
      if (get.failed()) {
        future.fail(get.cause());
        return;
      }

      boolean anyMatch = get.result().getResults().stream()
        .map(history -> authUtil.calculateHash(password.getPassword(), history.getSalt()))
        .anyMatch(hash -> get.result().getResults().stream()
          .anyMatch(history -> history.getHash().equals(hash)));

      future.complete(anyMatch);
    });

    return future;
  }

  private Future<Integer> getPasswordHistoryNumber(String okapiUrl, String token, String tenant) {
    Future<Integer> future = Future.future();
    HttpClient httpClient = vertx.createHttpClient();

    httpClient.getAbs(okapiUrl + PW_HISTORY_NUMBER_CONF_PATH)
      .putHeader(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
      .putHeader(RestVerticle.OKAPI_HEADER_TOKEN, token)
      .putHeader(RestVerticle.OKAPI_HEADER_TENANT, tenant)
      .exceptionHandler(throwable -> future.complete(DEFAULT_PASSWORDS_HISTORY_NUMBER))
      .handler(resp -> resp.bodyHandler(body -> {
        if (resp.statusCode() != HttpStatus.SC_OK) {
          future.complete(DEFAULT_PASSWORDS_HISTORY_NUMBER);
        } else {
          Configurations conf = body.toJsonObject().mapTo(Configurations.class);
          if (conf.getConfigs().isEmpty()) {
            future.complete(DEFAULT_PASSWORDS_HISTORY_NUMBER);
            return;
          }
          future.complete(Integer.valueOf(conf.getConfigs().get(0).getValue()));
        }
      })).end();

    return future;
  }
}
