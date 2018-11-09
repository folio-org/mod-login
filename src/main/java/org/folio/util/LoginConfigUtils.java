package org.folio.util;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.folio.rest.jaxrs.resource.support.ResponseDelegate;

import javax.ws.rs.core.Response;

public class LoginConfigUtils {

  /**
   * EvenBus queue
   */
  public static final String EVENT_CONFIG_PROXY_STORY_ADDRESS = "event-store-service.queue";
  public static final String EVENT_CONFIG_PROXY_CONFIG_ADDRESS = "event-config-service.queue";

  public static final String EVENT_LOG_API_MODULE = "EVENT_LOG";
  public static final String EVENT_LOG_API_CODE_STATUS = "STATUS";
  public static final String SNAPSHOTS_TABLE_EVENT_LOGS = "event_logs";

  private LoginConfigUtils() {
    //not called
  }

  public static AsyncResult<Response> createFutureResponse(ResponseDelegate delegate) {
    return Future.succeededFuture(delegate);
  }

  public static <T> T getResponseEntity(AsyncResult<JsonObject> asyncResult, Class<T> t) {
    return asyncResult.result().mapTo(t);
  }
}
