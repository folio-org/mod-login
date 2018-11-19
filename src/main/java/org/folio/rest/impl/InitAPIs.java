package org.folio.rest.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.serviceproxy.ServiceBinder;
import org.folio.rest.resource.interfaces.InitAPI;
import org.folio.services.PasswordStorageService;

import java.net.URL;
import java.util.MissingResourceException;

import static org.folio.util.LoginConfigUtils.PW_CONFIG_PROXY_STORY_ADDRESS;

/**
 * @author kurt
 */
public class InitAPIs implements InitAPI {
  private static final String CREDENTIAL_SCHEMA_PATH = "ramls/credentials.json";

  public void init(Vertx vertx, Context context, Handler<AsyncResult<Boolean>> resultHandler) {
    URL u = InitAPIs.class.getClassLoader().getResource(CREDENTIAL_SCHEMA_PATH);
    if (u == null) {
      resultHandler.handle(Future.failedFuture(new MissingResourceException(CREDENTIAL_SCHEMA_PATH, InitAPIs.class.getName(), CREDENTIAL_SCHEMA_PATH)));
    } else {
      new ServiceBinder(vertx)
        .setAddress(PW_CONFIG_PROXY_STORY_ADDRESS)
        .register(PasswordStorageService.class, PasswordStorageService.create(vertx));

      resultHandler.handle(Future.succeededFuture(true));
    }
  }
}
