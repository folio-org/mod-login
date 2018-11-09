package org.folio.services.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.folio.rest.jaxrs.model.LogEvent;
import org.folio.rest.jaxrs.model.LogEvents;
import org.folio.rest.jaxrs.model.LogResponse;
import org.folio.rest.jaxrs.model.LoggingEvent;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Offset;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.persist.interfaces.Results;
import org.folio.services.StorageService;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;
import org.z3950.zing.cql.cql2pgjson.FieldException;

import java.util.UUID;

import static org.folio.util.LoginConfigUtils.SNAPSHOTS_TABLE_EVENT_LOGS;

public class StorageServiceImpl implements StorageService {

  private static final String EVENT_CONFIG_ID = "id";
  private static final String SUCCESSFUL_MESSAGE_CREATE = "Event id: %s was successfully saved to event log";
  private static final String SUCCESSFUL_MESSAGE_DELETE = "Event was successfully deleted from event log";
  private static final String ERROR_MESSAGE_STORAGE_SERVICE = "Error while %s | message: %s";
  private static final String EVENT_CONFIG_CRITERIA_ID = "userId==%s";

  private final Logger logger = LoggerFactory.getLogger(StorageServiceImpl.class);
  private final Vertx vertx;

  public StorageServiceImpl(Vertx vertx) {
    this.vertx = vertx;
  }

  @Override
  public StorageService createEvent(String tenantId, JsonObject eventEntity,
                                    Handler<AsyncResult<JsonObject>> asyncResultHandler) {
    try {
      String id = UUID.randomUUID().toString();
      eventEntity.put(EVENT_CONFIG_ID, id);
      LogEvent eventConfig = eventEntity.mapTo(LogEvent.class);

      PostgresClient.getInstance(vertx, tenantId).save(SNAPSHOTS_TABLE_EVENT_LOGS, id, eventConfig,
        postReply -> {
          if (postReply.failed()) {
            String errorMessage = String.format(ERROR_MESSAGE_STORAGE_SERVICE,
              "saving the logging event to the db", postReply.cause().getMessage());
            logger.error(errorMessage);
            asyncResultHandler.handle(Future.failedFuture(postReply.cause()));
            return;
          }

          LogResponse logResponse = new LogResponse().withMessage(String.format(SUCCESSFUL_MESSAGE_CREATE, id));
          asyncResultHandler.handle(Future.succeededFuture(JsonObject.mapFrom(logResponse)));
        });
    } catch (Exception ex) {
      String errorMessage = String.format(ERROR_MESSAGE_STORAGE_SERVICE, "creating new logging event", ex.getMessage());
      logger.error(errorMessage);
      asyncResultHandler.handle(Future.failedFuture(errorMessage));
    }
    return this;
  }

  @Override
  public StorageService findAllEvents(String tenantId, int limit, int offset, String query,
                                      Handler<AsyncResult<JsonObject>> asyncResultHandler) {
    try {
      CQLWrapper cql = getCQL(query, limit, offset);
      String[] fieldList = {"*"};
      PostgresClient.getInstance(vertx, tenantId)
        .get(SNAPSHOTS_TABLE_EVENT_LOGS, LoggingEvent.class, fieldList, cql, true, false,
          getReply -> {
            if (getReply.failed()) {
              String errorMessage = String.format(ERROR_MESSAGE_STORAGE_SERVICE,
                "querying the db to get all event configurations", getReply.cause().getMessage());
              logger.error(errorMessage);
              asyncResultHandler.handle(Future.failedFuture(getReply.cause()));
              return;
            }

            Results<LoggingEvent> result = getReply.result();
            Integer totalRecords = result.getResultInfo().getTotalRecords();
            LogEvents eventEntries = new LogEvents()
              .withLoggingEvent(result.getResults())
              .withTotalRecords(totalRecords);

            JsonObject entries = JsonObject.mapFrom(eventEntries);
            asyncResultHandler.handle(Future.succeededFuture(entries));
          });
    } catch (Exception ex) {
      String errorMessage = String.format(ERROR_MESSAGE_STORAGE_SERVICE, "find the event by filter", ex.getMessage());
      logger.error(errorMessage);
      asyncResultHandler.handle(Future.failedFuture(errorMessage));
    }
    return this;
  }

  @Override
  public StorageService deleteEventByUserId(String tenantId, String userId,
                                            Handler<AsyncResult<JsonObject>> asyncResultHandler) {
    try {
      CQLWrapper cqlFilter = getCqlWrapper(userId);
      PostgresClient.getInstance(vertx, tenantId)
        .delete(SNAPSHOTS_TABLE_EVENT_LOGS, cqlFilter,
          deleteReply -> {
            if (deleteReply.failed()) {
              String errorMessage = String.format(ERROR_MESSAGE_STORAGE_SERVICE,
                "deleting the event configuration to the db", deleteReply.cause().getMessage());
              logger.error(errorMessage);
              asyncResultHandler.handle(Future.failedFuture(deleteReply.cause()));
              return;
            }

            int resultCode = deleteReply.result().getUpdated();
            if (resultCode == 0) {
              asyncResultHandler.handle(Future.succeededFuture(null));
              return;
            }

            LogResponse logResponse = new LogResponse().withMessage(SUCCESSFUL_MESSAGE_DELETE);
            JsonObject eventResponseJson = JsonObject.mapFrom(logResponse);
            asyncResultHandler.handle(Future.succeededFuture(eventResponseJson));
          });
    } catch (Exception ex) {
      String errorMessage = String.format(ERROR_MESSAGE_STORAGE_SERVICE, "deleting the logging event", ex.getMessage());
      logger.error(errorMessage);
      asyncResultHandler.handle(Future.failedFuture(errorMessage));
    }
    return this;
  }

  /**
   * Builds criteria wrapper
   *
   * @param value - value corresponding to the key
   * @return - CQLWrapper object
   */
  private CQLWrapper getCqlWrapper(String value) throws FieldException {
    CQL2PgJSON cql2PgJSON = new CQL2PgJSON(SNAPSHOTS_TABLE_EVENT_LOGS + ".jsonb");
    return new CQLWrapper(cql2PgJSON, String.format(EVENT_CONFIG_CRITERIA_ID, value));
  }

  /**
   * Build CQL from request URL query
   *
   * @param query - query from URL
   * @param limit - limit of records for pagination
   * @return - CQL wrapper for building postgres request to database
   */
  private CQLWrapper getCQL(String query, int limit, int offset) throws org.z3950.zing.cql.cql2pgjson.FieldException {
    CQL2PgJSON cql2pgJson = new CQL2PgJSON(SNAPSHOTS_TABLE_EVENT_LOGS + ".jsonb");
    return new CQLWrapper(cql2pgJson, query)
      .setLimit(new Limit(limit))
      .setOffset(new Offset(offset));
  }
}
