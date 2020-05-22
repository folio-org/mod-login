package org.folio.services.impl;

import static org.folio.rest.RestVerticle.OKAPI_HEADER_TENANT;
import static org.folio.rest.RestVerticle.OKAPI_HEADER_TOKEN;
import static org.folio.util.LoginConfigUtils.EVENT_LOG_API_CODE_STATUS;
import static org.folio.util.LoginConfigUtils.EVENT_LOG_API_MODULE;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.ws.rs.core.MediaType;

import org.folio.rest.jaxrs.model.Config;
import org.folio.rest.jaxrs.model.ConfigResponse;
import org.folio.rest.jaxrs.model.Configurations;
import org.folio.services.ConfigurationService;
import org.folio.util.WebClientFactory;

import com.google.common.collect.Lists;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;

public class ConfigurationServiceImpl implements ConfigurationService {

  private static final String OKAPI_URL_HEADER = "x-okapi-url";
  private static final String REQUEST_URI_PATH = "configurations/entries";
  private static final String REQUEST_URL_TEMPLATE = "%s/%s?query=module==%s";
  private static final String HTTP_HEADER_CONTENT_TYPE = HttpHeaders.CONTENT_TYPE.toString();
  private static final String HTTP_HEADER_ACCEPT = HttpHeaders.ACCEPT.toString();
  private static final String EVENT_LOG_STATUS_CODE = "statusCode";

  private static final Predicate<Config> HAS_EVENT_CONFIG_ENABLE_LOG_CONFIG = config -> config.getModule().equals(EVENT_LOG_API_MODULE) && config.getCode().equals(EVENT_LOG_API_CODE_STATUS);
  private static final Predicate<Config> HAS_EVENT_CONFIG_ENABLE_LOG_EVENS = config -> config.getModule().equals(EVENT_LOG_API_MODULE) && config.getEnabled();

  private static final String ERROR_LOOKING_UP_MOD_CONFIG = "Error looking up config at url=%s | Expected status code 200, got %s | error message: %s";

  private final Logger logger = LoggerFactory.getLogger(ConfigurationServiceImpl.class);

  public ConfigurationServiceImpl(Vertx vertx) {
  }

  @Override
  public ConfigurationService getEnableConfigurations(String tenantId, JsonObject headers, Handler<AsyncResult<JsonObject>> asyncResultHandler) {
    try {
      lookupConfig(headers, tenantId).onComplete(lookupConfigHandler -> {
        ConfigResponse configResponse = new ConfigResponse()
          .withCode(EVENT_LOG_STATUS_CODE)
          .withConfigs(Lists.newArrayList())
          .withEnabled(false);

        if (lookupConfigHandler.failed()) {
          asyncResultHandler.handle(Future.succeededFuture(JsonObject.mapFrom(configResponse)));
          return;
        }

        boolean isEnable = isStatusCodeEnable(lookupConfigHandler);
        configResponse.setEnabled(isEnable);
        if (!isEnable) {
          asyncResultHandler.handle(Future.succeededFuture(JsonObject.mapFrom(configResponse)));
          return;
        }

        List<String> enableConfigurations = getEnableConfigurations(lookupConfigHandler);
        configResponse.setConfigs(enableConfigurations);
        asyncResultHandler.handle(Future.succeededFuture(JsonObject.mapFrom(configResponse)));
      });
    } catch (Exception ex) {
      logger.error(ex.getMessage(), ex);
      asyncResultHandler.handle(Future.failedFuture(ex));
    }
    return this;
  }

  private boolean isStatusCodeEnable(AsyncResult<JsonObject> lookupConfigHandler) {
    Configurations configurations = lookupConfigHandler.result().mapTo(Configurations.class);
    return configurations.getConfigs().stream()
      .filter(HAS_EVENT_CONFIG_ENABLE_LOG_CONFIG)
      .map(Config::getEnabled)
      .findFirst()
      .orElse(false);
  }

  private List<String> getEnableConfigurations(AsyncResult<JsonObject> lookupConfigHandler) {
    Configurations configurations = lookupConfigHandler.result().mapTo(Configurations.class);
    return configurations.getConfigs().stream()
      .filter(HAS_EVENT_CONFIG_ENABLE_LOG_EVENS)
      .map(Config::getCode)
      .collect(Collectors.toList());
  }

  private Future<JsonObject> lookupConfig(JsonObject headers, String tenantId) {
    Promise<JsonObject> promise = Promise.promise();
    String okapiUrl = headers.getString(OKAPI_URL_HEADER);
    String okapiToken = headers.getString(OKAPI_HEADER_TOKEN);
    String requestUrl = String.format(REQUEST_URL_TEMPLATE, okapiUrl, REQUEST_URI_PATH, EVENT_LOG_API_MODULE);

    HttpRequest<Buffer> request = WebClientFactory.getWebClient().getAbs(requestUrl);
    request.putHeader(OKAPI_HEADER_TOKEN, okapiToken)
      .putHeader(OKAPI_HEADER_TENANT, tenantId)
      .putHeader(HTTP_HEADER_CONTENT_TYPE, MediaType.APPLICATION_JSON)
      .putHeader(HTTP_HEADER_ACCEPT, MediaType.APPLICATION_JSON)
      .send(ar -> {
        if (ar.failed()) {
          promise.fail(ar.cause());
        } else {
          HttpResponse<Buffer> response = ar.result();
          if (response.statusCode() != 200) {
            promise.fail(String.format(ERROR_LOOKING_UP_MOD_CONFIG, requestUrl, response.statusCode(), response.bodyAsString()));
          } else {
            promise.complete(response.bodyAsJsonObject());
          }
        }
      });
    return promise.future();
  }
}
