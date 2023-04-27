package org.folio.rest.impl;

import java.util.MissingResourceException;

import org.folio.rest.resource.interfaces.InitAPI;
import org.folio.services.ConfigurationService;
import org.folio.services.UserService;
import org.folio.services.LogStorageService;
import org.folio.services.PasswordStorageService;
import org.folio.util.ResourceUtil;
import org.folio.util.WebClientFactory;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.serviceproxy.ServiceBinder;

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
      new ServiceBinder(vertx)
          .setAddress(MOD_USERS_PROXY_ADDRESS)
          .register(UserService.class, UserService.create(vertx));
      return true;
    })
    .onComplete(resultHandler::handle);
  }

  static Future<Void> checkResource(String name) {
    try {
      // works without jar, with jar, and without and with IDEs (Eclipse, ...)
      ResourceUtil.asString(name);
      return Future.succeededFuture();
    } catch (Exception e) {
      var e2 = new MissingResourceException(name, InitAPIs.class.getName(), name);
      return Future.failedFuture(e2);
    }
  }
}
