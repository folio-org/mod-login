package org.folio.rest.impl;

import io.vertx.core.*;
import io.vertx.serviceproxy.ServiceBinder;
import org.folio.rest.resource.interfaces.InitAPI;
import org.folio.services.ConfigurationService;
import org.folio.services.StorageService;

import java.net.URL;
import java.util.MissingResourceException;

import static org.folio.util.LoginConfigUtils.EVENT_CONFIG_PROXY_CONFIG_ADDRESS;
import static org.folio.util.LoginConfigUtils.EVENT_CONFIG_PROXY_STORY_ADDRESS;

/**
 * Performs preprocessing operations before the verticle is deployed,
 * e.g. components registration, initializing, binding.
 *
 * @author kurt
 */
public class InitAPIs implements InitAPI {
  private static final String CREDENTIAL_SCHEMA_PATH = "ramls/credentials.json";

  public void init(Vertx vertx, Context context, Handler<AsyncResult<Boolean>> resultHandler) {
    URL u = InitAPIs.class.getClassLoader().getResource(CREDENTIAL_SCHEMA_PATH);
    if(u == null) {
      resultHandler.handle(Future.failedFuture(new MissingResourceException(CREDENTIAL_SCHEMA_PATH, InitAPIs.class.getName(), CREDENTIAL_SCHEMA_PATH)));
    } else {
      new ServiceBinder(vertx)
        .setAddress(EVENT_CONFIG_PROXY_STORY_ADDRESS)
        .register(StorageService.class, StorageService.create(vertx));
      new ServiceBinder(vertx)
        .setAddress(EVENT_CONFIG_PROXY_CONFIG_ADDRESS)
        .register(ConfigurationService.class, ConfigurationService.create(vertx));

      resultHandler.handle(Future.succeededFuture(true));
    }
  }
}
