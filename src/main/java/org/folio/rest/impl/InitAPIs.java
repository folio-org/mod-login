package org.folio.rest.impl;

import static org.folio.util.LoginConfigUtils.EVENT_CONFIG_PROXY_CONFIG_ADDRESS;
import static org.folio.util.LoginConfigUtils.EVENT_CONFIG_PROXY_STORY_ADDRESS;
import static org.folio.util.LoginConfigUtils.PW_CONFIG_PROXY_STORY_ADDRESS;

import java.util.MissingResourceException;
import org.folio.rest.resource.interfaces.InitAPI;
import org.folio.services.ConfigurationService;
import org.folio.services.LogStorageService;
import org.folio.services.PasswordStorageService;
import org.folio.util.WebClientFactory;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.serviceproxy.ServiceBinder;

/**
 * Performs preprocessing operations before the verticle is deployed,
 * e.g. components registration, initializing, binding.
 *
 * @author kurt
 */
public class InitAPIs implements InitAPI {
  private static final String CREDENTIAL_SCHEMA_PATH = "ramls/credentials.json";

  public void init(Vertx vertx, Context context, Handler<AsyncResult<Boolean>> resultHandler) {
    WebClientFactory.init(vertx);

    checkResource(CREDENTIAL_SCHEMA_PATH)
    .map(x -> {
      new ServiceBinder(vertx)
        .setAddress(PW_CONFIG_PROXY_STORY_ADDRESS)
        .register(PasswordStorageService.class, PasswordStorageService.create(vertx));
      new ServiceBinder(vertx)
        .setAddress(EVENT_CONFIG_PROXY_STORY_ADDRESS)
        .register(LogStorageService.class, LogStorageService.create(vertx));
      new ServiceBinder(vertx)
        .setAddress(EVENT_CONFIG_PROXY_CONFIG_ADDRESS)
        .register(ConfigurationService.class, ConfigurationService.create(vertx));
      return true;
    })
    .onComplete(resultHandler::handle);
  }

  private Future<Void> checkResource(String name) {
    // when running tests in IDEs (Eclipse, ...) getResourceAsStream works, getResource doesn't
    try (var x = InitAPIs.class.getResourceAsStream(CREDENTIAL_SCHEMA_PATH)) {
      return Future.succeededFuture();
    } catch (Exception e) {
      var e2 = new MissingResourceException(CREDENTIAL_SCHEMA_PATH, InitAPIs.class.getName(), CREDENTIAL_SCHEMA_PATH);
      return Future.failedFuture(e2);
    }
  }
}
