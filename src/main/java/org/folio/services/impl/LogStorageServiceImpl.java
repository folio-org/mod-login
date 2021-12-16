package org.folio.services.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.folio.rest.jaxrs.model.ConfigResponse;
import org.folio.rest.jaxrs.model.LogEvent;
import org.folio.rest.jaxrs.model.LogEvents;
import org.folio.rest.jaxrs.model.LogResponse;
import org.folio.rest.jaxrs.model.LoggingEvent;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.persist.interfaces.Results;
import org.folio.services.ConfigurationService;
import org.folio.services.LogStorageService;
import org.folio.util.EventLogUtils;
import org.folio.util.StringUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.cql2pgjson.CQL2PgJSON;
import org.folio.cql2pgjson.exception.FieldException;

import java.util.List;
import java.util.UUID;

import static org.folio.rest.impl.LoginAPI.MESSAGE_LOG_CONFIGURATION_IS_DISABLED;
import static org.folio.rest.impl.LoginAPI.MESSAGE_LOG_EVENT_IS_DISABLED;
import static org.folio.util.LoginConfigUtils.EMPTY_JSON_OBJECT;
import static org.folio.util.LoginConfigUtils.SNAPSHOTS_TABLE_EVENT_LOGS;
import static org.folio.util.LoginConfigUtils.getResponseEntity;

public class LogStorageServiceImpl implements LogStorageService {

  private static final String EVENT_CONFIG_ID = "id";
  private static final String SUCCESSFUL_MESSAGE_CREATE = "Event id: %s was successfully saved to event log";
  private static final String SUCCESSFUL_MESSAGE_DELETE = "Event was successfully deleted from event log";
  private static final String ERROR_MESSAGE_STORAGE_SERVICE = "Error while %s | message: %s";
  private static final String EVENT_CONFIG_CRITERIA_ID = "userId==%s";

  private final Logger logger = LogManager.getLogger(LogStorageServiceImpl.class);
  private final Vertx vertx;

  private ConfigurationService configurationService;

  public LogStorageServiceImpl(Vertx vertx) {
    this.vertx = vertx;
    configurationService = new ConfigurationServiceImpl(vertx);
  }


  public LogStorageService logEvent(String tenantId, String userId, LogEvent.EventType eventType, JsonObject headers) {
    LogEvent logEvent = EventLogUtils.createLogEventObject(eventType, userId, headers);
    configurationService.getEnableConfigurations(tenantId, headers, serviceHandler -> {
      if (serviceHandler.failed()) {
        logger.error(serviceHandler.cause().getMessage());
        return;
      }
      ConfigResponse responseEntity = getResponseEntity(serviceHandler, ConfigResponse.class);
      if (!responseEntity.getEnabled()) {
        logger.info(MESSAGE_LOG_CONFIGURATION_IS_DISABLED);
        return;
      }
      List<String> enableConfigCodes = responseEntity.getConfigs();
      String eventCode = logEvent.getEventType().toString();
      if (!enableConfigCodes.contains(eventCode)) {
        logger.info(String.format(MESSAGE_LOG_EVENT_IS_DISABLED, eventCode));
        return;
      }

      JsonObject loggingEventJson = JsonObject.mapFrom(logEvent);
      createEvent(tenantId, loggingEventJson,
        storageHandler -> {
          if (storageHandler.failed()) {
            logger.error(storageHandler.cause().getMessage());
          }
        });
    });

    return this;
  }

  @Override
  public LogStorageService createEvent(String tenantId, JsonObject eventEntity,
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
  public LogStorageService findAllEvents(String tenantId, int limit, int offset, String query,
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
  public LogStorageService deleteEventByUserId(String tenantId, String userId,
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

            int resultCode = deleteReply.result().rowCount();
            if (resultCode == 0) {
              asyncResultHandler.handle(Future.succeededFuture(EMPTY_JSON_OBJECT));
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
  static CQLWrapper getCqlWrapper(String value) throws FieldException {
    CQL2PgJSON cql2PgJSON = new CQL2PgJSON(SNAPSHOTS_TABLE_EVENT_LOGS + ".jsonb");
    return new CQLWrapper(cql2PgJSON, String.format(EVENT_CONFIG_CRITERIA_ID, StringUtil.cqlEncode(value)));
  }

  /**
   * Build CQL from request URL query
   *
   * @param query - query from URL
   * @param limit - limit of records for pagination
   * @return - CQL wrapper for building postgres request to database
   */
  private CQLWrapper getCQL(String query, int limit, int offset) throws FieldException {
    CQL2PgJSON cql2pgJson = new CQL2PgJSON(SNAPSHOTS_TABLE_EVENT_LOGS + ".jsonb");
    return new CQLWrapper(cql2pgJson, query, limit, offset);
  }
}
