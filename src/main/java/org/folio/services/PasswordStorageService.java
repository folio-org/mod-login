package org.folio.services;

import io.vertx.codegen.annotations.Fluent;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.folio.rest.jaxrs.model.PasswordCreate;
import org.folio.rest.jaxrs.model.PasswordReset;
import org.folio.rest.jaxrs.model.ResponseCreateAction;
import org.folio.rest.jaxrs.model.ResponseResetAction;
import org.folio.services.impl.PasswordStorageServiceImpl;

import java.util.Map;

/**
 * The interface provides basic CRUD operations for storing and retrieving data from the storage
 */
@ProxyGen
public interface PasswordStorageService {

  static PasswordStorageService create(Vertx vertx) {
    return new PasswordStorageServiceImpl(vertx);
  }

  /**
   * Creates proxy instance that helps to push message into the message queue
   *
   * @param vertx   vertx instance
   * @param address host address
   * @return LogStorageService instance
   */
  static PasswordStorageService createProxy(Vertx vertx, String address) {
    return new PasswordStorageServiceVertxEBProxy(vertx, address);
  }

  /**
   * Save the change password action in the repository.
   * The method returns the `passwordExists` parameter of entity {@link ResponseCreateAction}
   * that indicates the existence of user credentials in the db.
   *
   * @param tenantId       tenant identifier
   * @param passwordEntity Json representation of the passwordEntity {@link PasswordCreate}
   * @return asyncResult with the response entity {@link ResponseCreateAction}
   */
  @Fluent
  PasswordStorageService savePasswordAction(String tenantId, JsonObject passwordEntity,
                                            Handler<AsyncResult<JsonObject>> asyncResultHandler);

  /**
   * Retrieves action record by id
   *
   * @param tenantId tenant identifier
   * @param actionId action identifier of entity {@link PasswordCreate}
   * @return asyncResult with the response entity {@link PasswordCreate}
   */
  @Fluent
  PasswordStorageService findPasswordActionById(String tenantId, String actionId,
                                                Handler<AsyncResult<JsonObject>> asyncResultHandler);

  /**
   * Resets password for user in record and deletes action record
   * The method returns a parameter
   * indicating whether the user previously had any credentials.
   *
   * @param okapiHeaders okapi headers
   * @param resetAction Json representation of the entity {@link PasswordReset}
   * @return asyncResult with the response entity {@link ResponseResetAction}
   */
  @Fluent
  PasswordStorageService resetPassword(Map<String, String> okapiHeaders, JsonObject resetAction,
                                       Handler<AsyncResult<JsonObject>> asyncResultHandler);

  /**
   * Updates user credential.
   *
   * @param credJson  Json representation of the {@link org.folio.rest.jaxrs.model.Credential} entity
   * @param okapiHeaders okapi headers
   * @param asyncResultHandler update response handler with {@code Boolean.TRUE} if update is successful
   * @return {@link PasswordStorageService} instance
   */
  @Fluent
  PasswordStorageService updateCredential(JsonObject credJson, Map<String, String> okapiHeaders,
                                          Handler<AsyncResult<Void>> asyncResultHandler);

  /**
   * Checks if given password is previously used.
   *
   * @param passwordEntity Json representation of the {@link org.folio.rest.jaxrs.model.Password} entity
   * @param okapiHeaders okapi headers
   * @param asyncResultHandler response handler with {@code Boolean.TRUE} if password is previously used,
   * {@code Boolean.FALSE} otherwise
   * @return {@link PasswordStorageService} instance
   */
  @Fluent
  PasswordStorageService isPasswordPreviouslyUsed(JsonObject passwordEntity, Map<String, String> okapiHeaders,
                                                  Handler<AsyncResult<Boolean>> asyncResultHandler);
}
