package org.folio.services;

import io.vertx.codegen.annotations.Fluent;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.folio.rest.jaxrs.model.ConfigResponse;
import org.folio.services.impl.ConfigurationServiceImpl;

/**
 * The interface provides a HTTP request to mo-configuration to get the configuration
 */
@ProxyGen
public interface ConfigurationService {

  static ConfigurationService create(Vertx vertx) {
    return new ConfigurationServiceImpl(vertx);
  }

  /**
   * Creates proxy instance that helps to push message into the message queue
   *
   * @param vertx   vertx instance
   * @param address host address
   * @return LogStorageService instance
   */
  static ConfigurationService createProxy(Vertx vertx, String address) {
    return new ConfigurationServiceVertxEBProxy(vertx, address);
  }

  /**
   * Get configurations
   *
   * @param tenantId tenant identifier
   * @param headers  okapi headers
   * @return asyncResult with the entity  {@link ConfigResponse}
   */
  @Fluent
  ConfigurationService getEnableConfigurations(String tenantId, JsonObject headers, Handler<AsyncResult<JsonObject>> asyncResultHandler);
}
