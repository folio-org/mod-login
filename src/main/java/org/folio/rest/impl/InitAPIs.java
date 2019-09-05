package org.folio.rest.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.serviceproxy.ServiceBinder;
import org.folio.rest.resource.interfaces.InitAPI;
import org.folio.services.ConfigurationService;
import org.folio.services.LogStorageService;
import org.folio.services.PasswordStorageService;

import java.net.URL;
import java.util.MissingResourceException;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.util.Constants.DEFAULT_TIMEOUT;
import static org.folio.util.Constants.LOOKUP_TIMEOUT;
import static org.folio.util.LoginConfigUtils.*;

/**
 * Performs preprocessing operations before the verticle is deployed,
 * e.g. components registration, initializing, binding.
 *
 * @author kurt
 */
public class InitAPIs implements InitAPI {
  private static final String CREDENTIAL_SCHEMA_PATH = "ramls/credentials.json";

  public void init(Vertx vertx, Context context, Handler<AsyncResult<Boolean>> resultHandler) {
    final int timeout = Integer.parseInt(MODULE_SPECIFIC_ARGS.getOrDefault(LOOKUP_TIMEOUT, DEFAULT_TIMEOUT));
    context.put("httpClient", vertx.createHttpClient(new HttpClientOptions()
        .setConnectTimeout(timeout)
        .setIdleTimeout(timeout)));
    URL u = InitAPIs.class.getClassLoader().getResource(CREDENTIAL_SCHEMA_PATH);
    if (u == null) {
      resultHandler.handle(Future.failedFuture(new MissingResourceException(CREDENTIAL_SCHEMA_PATH, InitAPIs.class.getName(), CREDENTIAL_SCHEMA_PATH)));
    } else {
      new ServiceBinder(vertx)
        .setAddress(PW_CONFIG_PROXY_STORY_ADDRESS)
        .register(PasswordStorageService.class, PasswordStorageService.create(vertx));
      new ServiceBinder(vertx)
        .setAddress(EVENT_CONFIG_PROXY_STORY_ADDRESS)
        .register(LogStorageService.class, LogStorageService.create(vertx));
      new ServiceBinder(vertx)
        .setAddress(EVENT_CONFIG_PROXY_CONFIG_ADDRESS)
        .register(ConfigurationService.class, ConfigurationService.create(vertx));

      resultHandler.handle(Future.succeededFuture(true));
    }
  }
}
