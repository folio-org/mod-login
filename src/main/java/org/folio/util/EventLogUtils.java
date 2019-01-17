package org.folio.util;

import io.vertx.core.json.JsonObject;
import org.folio.rest.RestVerticle;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.jaxrs.model.LogEvent;

import javax.ws.rs.core.HttpHeaders;
import java.util.Date;
import java.util.Optional;

import static org.folio.rest.impl.LoginAPI.X_FORWARDED_FOR_HEADER;

public class EventLogUtils {

  private EventLogUtils() {
    throw new IllegalStateException("Utility class");
  }

  public static LogEvent createLogEventObject(LogEvent.EventType eventType, String userId,
                                              JsonObject requestHeaders) {



    return new LogEvent()
      .withEventType(eventType)
      .withUserId(userId)
      .withTenant(requestHeaders.getString(RestVerticle.OKAPI_HEADER_TENANT))
      .withBrowserInformation(requestHeaders.getString(HttpHeaders.USER_AGENT))
      .withTimestamp(new Date(Long.parseLong(requestHeaders.getString(LoginAPI.OKAPI_REQUEST_TIMESTAMP_HEADER))))
      .withIp(Optional.ofNullable(requestHeaders.getString(X_FORWARDED_FOR_HEADER))
        .orElseGet(() -> requestHeaders.getString(LoginAPI.OKAPI_REQUEST_IP_HEADER)));
  }

}
