package org.folio.rest.impl;
import java.util.Map;

import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.rest.annotations.Validate;
import org.folio.rest.jaxrs.model.TenantAttributes;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import org.folio.rest.tools.utils.TenantLoading;

public class TenantRefAPI extends TenantAPI {

  private static final Logger log = LogManager.getLogger(TenantRefAPI.class);

  private String filter(String content) {
    JsonObject jInput = new JsonObject(content);
    JsonObject jOutput = new JsonObject();
    jOutput.put("userId", jInput.getString("id"));
    jOutput.put("username", jInput.getString("username"));
    jOutput.put("password", jInput.getString("username"));
    return jOutput.encodePrettily();
  }

  @Validate
  @Override
  public void postTenant(TenantAttributes tenantAttributes, Map<String, String> headers,
      Handler<AsyncResult<Response>> handler, Context context) {
    super.postTenantSync(tenantAttributes, headers, handler, context);
  }
  
  @Validate
  @Override
  Future<Integer> loadData(TenantAttributes attributes, String tenantId,
                           Map<String, String> headers, Context vertxContext) {
    log.info("load sample");
    return super.loadData(attributes, tenantId, headers, vertxContext)
        .compose(superRecordsLoaded -> {
          return new TenantLoading()
              .withKey("loadSample").withLead("sample-data")
              .withPostOnly()
              .withFilter(this::filter)
              .withAcceptStatus(422)
              .add("users", "authn/credentials")
              .perform(attributes, headers, vertxContext, superRecordsLoaded);
        });
  }
}
