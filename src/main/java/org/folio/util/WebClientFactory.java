package org.folio.util;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.util.Constants.DEFAULT_TIMEOUT;
import static org.folio.util.Constants.LOOKUP_TIMEOUT;

import java.util.HashMap;
import java.util.Map;

import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

public class WebClientFactory {

  private static Map<Vertx, WebClient> clients = new HashMap<>();

  public static WebClient getWebClient(Vertx vertx) {
    return clients.get(vertx);
  }

  private WebClientFactory() {
  }

  public static void init(Vertx vertx) {
    int lookupTimeout = Integer.parseInt(MODULE_SPECIFIC_ARGS.getOrDefault(LOOKUP_TIMEOUT, DEFAULT_TIMEOUT));

    WebClientOptions options = new WebClientOptions();
    options.setConnectTimeout(lookupTimeout);
    options.setIdleTimeout(lookupTimeout);
    clients.put(vertx, WebClient.create(vertx, options));
  }
}
