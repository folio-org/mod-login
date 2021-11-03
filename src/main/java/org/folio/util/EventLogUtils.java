package org.folio.util;

import org.folio.okapi.common.XOkapiHeaders;
import org.folio.rest.jaxrs.model.LogEvent;

import javax.ws.rs.core.HttpHeaders;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.folio.rest.impl.LoginAPI.X_FORWARDED_FOR_HEADER;

public class EventLogUtils {

  private EventLogUtils() {
    throw new IllegalStateException("Utility class");
  }

  public static LogEvent createLogEventObject(LogEvent.EventType eventType, String userId,
                                              Map<String,String> requestHeaders) {
    return new LogEvent()
      .withId(UUID.randomUUID().toString())
      .withEventType(eventType)
      .withUserId(userId)
      .withTenant(requestHeaders.get(XOkapiHeaders.TENANT))
      .withBrowserInformation(requestHeaders.get(HttpHeaders.USER_AGENT))
      .withTimestamp(new Date(Long.parseLong(requestHeaders.get(XOkapiHeaders.REQUEST_TIMESTAMP))))
      .withIp(Optional.ofNullable(requestHeaders.get(X_FORWARDED_FOR_HEADER))
        .orElseGet(() -> requestHeaders.get(XOkapiHeaders.REQUEST_IP)));
  }

}
