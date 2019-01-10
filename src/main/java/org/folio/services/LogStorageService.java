package org.folio.services;

import io.vertx.codegen.annotations.Fluent;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.folio.rest.jaxrs.model.LogEvent;
import org.folio.rest.jaxrs.model.LogEvents;
import org.folio.rest.jaxrs.model.LogResponse;
import org.folio.services.impl.LogStorageServiceImpl;

/**
 * The interface provides basic CRUD operations for storing and retrieving data from the storage
 */
@ProxyGen
public interface LogStorageService {

  static LogStorageService create(Vertx vertx) {
    return new LogStorageServiceImpl(vertx);
  }

  /**
   * Creates proxy instance that helps to push message into the message queue
   *
   * @param vertx   vertx instance
   * @param address host address
   * @return LogStorageService instance
   */
  static LogStorageService createProxy(Vertx vertx, String address) {
    return new LogStorageServiceVertxEBProxy(vertx, address);
  }

  /**
   * Save entity {@link LogEvent}
   *
   * @param tenantId    tenant identifier
   * @param eventEntity Json representation of the entity {@link LogEvent}
   * @return asyncResult with the entity {@link LogResponse}
   */
  @Fluent
  LogStorageService createEvent(String tenantId, JsonObject eventEntity, Handler<AsyncResult<JsonObject>> asyncResultHandler);

  /**
   * Find the entity {@link LogEvents} by filter
   *
   * @param tenantId tenant identifier
   * @param limit    maximum number of results
   * @param offset   offset index in a list of results
   * @param query    query string to filter users based on matching criteria in fields
   * @return asyncResult with the entity {@link LogEvents}
   */
  @Fluent
  LogStorageService findAllEvents(String tenantId, int limit, int offset, String query, Handler<AsyncResult<JsonObject>> asyncResultHandler);

  /**
   * Delete the entity {@link LogEvent} by userId
   *
   * @param tenantId tenant identifier
   * @param userId   user identifier
   * @return asyncResult with the entity {@link LogResponse}
   */
  @Fluent
  LogStorageService deleteEventByUserId(String tenantId, String userId, Handler<AsyncResult<JsonObject>> asyncResultHandler);

  /**
   * Logs given event
   * @param tenantId    tenant id
   * @param headers     okapi headers
   * @param eventEntity event entity
   */
  @Fluent
  LogStorageService logEvent(String tenantId, JsonObject headers, JsonObject eventEntity);
}
