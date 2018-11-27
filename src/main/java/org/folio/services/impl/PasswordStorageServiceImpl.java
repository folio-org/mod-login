package org.folio.services.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.sql.SQLConnection;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.Metadata;
import org.folio.rest.jaxrs.model.PasswordCreate;
import org.folio.rest.jaxrs.model.PasswordReset;
import org.folio.rest.jaxrs.model.ResponseCreateAction;
import org.folio.rest.jaxrs.model.ResponseResetAction;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.PostgresClient;
import org.folio.services.PasswordStorageService;
import org.folio.util.AuthUtil;

import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.folio.util.LoginConfigUtils.*;

public class PasswordStorageServiceImpl implements PasswordStorageService {

  private static final String ID_FIELD = "'id'";
  private static final String USER_ID_FIELD = "'userId'";
  private static final String PW_ACTION_ID = "id";
  private static final String ERROR_MESSAGE_STORAGE_SERVICE = "Error while %s | message: %s";

  private final Logger logger = LoggerFactory.getLogger(PasswordStorageServiceImpl.class);
  private final Vertx vertx;

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
  public PasswordStorageService resetPassword(String tenantId, JsonObject resetActionEntity,
                                              Handler<AsyncResult<JsonObject>> asyncHandler) {
    try {
      PasswordReset passwordReset = resetActionEntity.mapTo(PasswordReset.class);
      PostgresClient pgClient = PostgresClient.getInstance(vertx, tenantId);

      pgClient.startTx(beginTx ->
        findUserIdByActionId(pgClient, beginTx, passwordReset, asyncHandler));
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
  private void findUserIdByActionId(PostgresClient pgClient, AsyncResult<SQLConnection> beginTx,
                                    PasswordReset passwordReset, Handler<AsyncResult<JsonObject>> asyncHandler) {
    String actionId = passwordReset.getPasswordResetActionId();
    Criterion criterionId = getCriterionId(actionId, ID_FIELD);

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
        findUserCredentialById(pgClient, beginTx, passwordReset, userId, asyncHandler);
      });
  }

  /**
   * Find user's credential by userId in `auth_credentials` table
   */
  private void findUserCredentialById(PostgresClient pgClient, AsyncResult<SQLConnection> beginTx,
                                      PasswordReset resetAction, String userId,
                                      Handler<AsyncResult<JsonObject>> asyncHandler) {
    Criterion criterion = getCriterionId(userId, USER_ID_FIELD);
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
          saveUserCredential(pgClient, beginTx, asyncHandler, actionId, userCredential, true);
        } else {
          Credential userCredential = createCredential(newPassword, credentialOpt.get());
          changeUserCredential(pgClient, beginTx, asyncHandler, actionId, userCredential);
        }
      });
  }

  /**
   * Save a new user's credential
   */
  private void changeUserCredential(PostgresClient pgClient, AsyncResult<SQLConnection> beginTx,
                                    Handler<AsyncResult<JsonObject>> asyncHandler,
                                    String actionId, Credential userCredential) {
    String credId = userCredential.getId();
    Criterion criterionId = getCriterionId(credId, ID_FIELD);
    pgClient.delete(beginTx, SNAPSHOTS_TABLE_CREDENTIALS, criterionId,
      deleteReply ->
      {
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

        saveUserCredential(pgClient, beginTx, asyncHandler, actionId, userCredential, false);
      });
  }

  /**
   * Save a new user's credential
   */
  private void saveUserCredential(PostgresClient pgClient, AsyncResult<SQLConnection> beginTx,
                                  Handler<AsyncResult<JsonObject>> asyncHandler, String actionId,
                                  Credential userCredential, boolean isNewPassword) {
    String credId = userCredential.getId();
    pgClient.save(beginTx, SNAPSHOTS_TABLE_CREDENTIALS, credId, userCredential,
      putReply -> {
        if (putReply.failed() || !putReply.result().equals(credId)) {
          pgClient.rollbackTx(beginTx,
            rollbackTx ->
              asyncHandler.handle(Future.failedFuture(rollbackTx.cause())));
          return;
        }

        deletePasswordActionById(pgClient, beginTx, asyncHandler, actionId, isNewPassword);
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
    AuthUtil authUtil = new AuthUtil();
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
}
