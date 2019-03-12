package org.folio.rest.impl;
import java.util.Map;
import javax.ws.rs.core.Response;
import org.folio.rest.jaxrs.model.TenantAttributes;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.folio.rest.tools.utils.TenantLoading;

public class TenantRefAPI extends TenantAPI {

  private static final Logger log = LoggerFactory.getLogger(TenantRefAPI.class);

  private String filter(String content) {
    JsonObject jInput = new JsonObject(content);
    JsonObject jOutput = new JsonObject();
    jOutput.put("userId", jInput.getString("id"));
    jOutput.put("username", jInput.getString("username"));
    jOutput.put("password", jInput.getString("username"));
    return jOutput.encodePrettily();
  }

  @Override
  public void postTenant(TenantAttributes ta, Map<String, String> headers,
    Handler<AsyncResult<Response>> hndlr, Context cntxt) {
    log.info("postTenant");
    Vertx vertx = cntxt.owner();
    super.postTenant(ta, headers, res -> {
      if (res.failed()) {
        hndlr.handle(res);
        return;
      }
      TenantLoading tl = new TenantLoading();
      tl.withKey("loadSample").withLead("sample-data")
        .withPostOnly()
        .withFilter(this::filter)
        .withAcceptStatus(422)
        .add("users", "authn/credentials")
        .perform(ta, headers, vertx, res1 -> {
          if (res1.failed()) {
            hndlr.handle(io.vertx.core.Future.succeededFuture(PostTenantResponse
              .respond500WithTextPlain(res1.cause().getLocalizedMessage())));
            return;
          }
          hndlr.handle(io.vertx.core.Future.succeededFuture(PostTenantResponse
            .respond201WithApplicationJson("")));
        });
    }, cntxt);
  }

}
