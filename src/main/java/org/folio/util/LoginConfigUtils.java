package org.folio.util;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.folio.rest.jaxrs.resource.support.ResponseDelegate;

import javax.ws.rs.core.Response;
import java.util.Map;

public class LoginConfigUtils {

  /**
   * EvenBus queue
   */
  public static final String EVENT_CONFIG_PROXY_STORY_ADDRESS = "event-store-service.queue";
  public static final String EVENT_CONFIG_PROXY_CONFIG_ADDRESS = "event-config-service.queue";
  public static final String PW_CONFIG_PROXY_STORY_ADDRESS = "password-store-service.queue";

  public static final String EVENT_LOG_API_MODULE = "EVENT_LOG";
  public static final String EVENT_LOG_API_CODE_STATUS = "STATUS";
  public static final String SNAPSHOTS_TABLE_EVENT_LOGS = "event_logs";

  /**
   * Tables
   */
  public static final String SNAPSHOTS_TABLE_PW = "auth_password_action";
  public static final String SNAPSHOTS_TABLE_CREDENTIALS = "auth_credentials";

  public static final String VALUE_IS_NOT_FOUND = "isNotFound";
  public static final JsonObject EMPTY_JSON_OBJECT = new JsonObject().put(VALUE_IS_NOT_FOUND, true);

  private LoginConfigUtils() {
    //not called
  }

  public static AsyncResult<Response> createFutureResponse(ResponseDelegate delegate) {
    return Future.succeededFuture(delegate);
  }

  public static <T> T getResponseEntity(AsyncResult<JsonObject> asyncResult, Class<T> t) {
    return asyncResult.result().mapTo(t);
  }

  public static JsonObject encodeJsonHeaders(Map<String,String> headers) {
    return JsonObject.mapFrom(headers);
  }

  public static Map<String,String> decodeJsonHeaders(JsonObject obj) {
    return obj.mapTo(CaseInsensitiveMap.class);
  }

}
