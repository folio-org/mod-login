package org.folio.util;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.util.Constants.DEFAULT_TIMEOUT;
import static org.folio.util.Constants.LOOKUP_TIMEOUT;

import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

public class WebClientFactory {

  private WebClient client;

  public static WebClient getWebClient() {
    return WebClientSingletonHolder.INSTANCE.client;
  }

  private WebClientFactory() {
    int lookupTimeout = Integer.parseInt(MODULE_SPECIFIC_ARGS.getOrDefault(LOOKUP_TIMEOUT, DEFAULT_TIMEOUT));

    WebClientOptions options = new WebClientOptions();
    options.setConnectTimeout(lookupTimeout);
    options.setIdleTimeout(lookupTimeout);
    client = WebClient.create(Vertx.vertx(), options);
  }
  
  private static class WebClientSingletonHolder {
    private static final WebClientFactory INSTANCE = new WebClientFactory();
  }
}
