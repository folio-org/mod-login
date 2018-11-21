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
import org.folio.services.impl.PasswordStorageServiceImpl;

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
   * Saves action to storage
   *
   * @param tenantId       tenant identifier
   * @param passwordEntity Json representation of the passwordEntity {@link PasswordCreate}
   * @return asyncResult with the entity {@link ResponseCreateAction}
   */
  @Fluent
  PasswordStorageService savePasswordAction(String tenantId, JsonObject passwordEntity, Handler<AsyncResult<JsonObject>> asyncResultHandler);

  /**
   * Retrieves action record by id
   *
   * @param tenantId tenant identifier
   * @param actionId action identifier
   * @return asyncResult with the entity {@link PasswordCreate}
   */
  @Fluent
  PasswordStorageService findPasswordEntityById(String tenantId, String actionId, Handler<AsyncResult<JsonObject>> asyncResultHandler);

  /**
   * Resets password for user in record and deletes action record
   *
   * @param tenantId       tenant identifier
   * @param resetAction Json representation of the entity {@link PasswordReset}
   * @return asyncResult with the entity {@link PasswordReset}
   */
  @Fluent
  PasswordStorageService resetPassword(String tenantId, JsonObject resetAction, Handler<AsyncResult<JsonObject>> asyncResultHandler);
}
