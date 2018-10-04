package org.folio.rest.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import java.net.URL;
import java.util.MissingResourceException;
import org.folio.rest.resource.interfaces.InitAPI;

/**
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
      resultHandler.handle(Future.succeededFuture(true));
    }
  }

}
