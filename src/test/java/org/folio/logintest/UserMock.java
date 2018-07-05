package org.folio.logintest;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import static java.lang.Thread.sleep;

/**
 *
 * @author kurt
 */
public class UserMock extends AbstractVerticle {
  
  public static final String gollumId = "bc6e4932-6415-40e2-ac1e-67ecdd665366";
  public static final String bombadilId = "35bbcda7-866a-4231-b478-59b9dd2eb3ee";
  public static final String sarumanId = "340bafb8-ea74-4f51-be8c-ec6493fd517e";

  public void start(Future<Void> future) {
    final int port = context.config().getInteger("port");

    Router router = Router.router(vertx);
    HttpServer server = vertx.createHttpServer();

    router.route("/users").handler(this::handleUsers);
    router.route("/token").handler(this::handleToken);
    System.out.println("Running UserMock on port " + port);
    server.requestHandler(router::accept).listen(port, result -> {
      if(result.failed()) {
        future.fail(result.cause());
      } else {
        future.complete();
      }
    });
  }

  private void handleUsers(RoutingContext context) {
    try {
      String query = context.request().getParam("query");
      if(query.equals("username==gollum")) {
        JsonObject userOb = new JsonObject()
                .put("username", "gollum")
                .put("id", gollumId)
                .put("active", true);
        JsonObject responseOb = new JsonObject()
                .put("users", new JsonArray()
                  .add(userOb))
                .put("totalRecords", 1);
        context.response()
                .setStatusCode(200)
                .end(responseOb.encode());

      } else if(query.equals("username==bombadil")) {
        sleep(500); //Bombadil gets delayed on purpose
        JsonObject userOb = new JsonObject()
                .put("username", "bombadil")
                .put("id", bombadilId)
                .put("active", true);
        JsonObject responseOb = new JsonObject()
                .put("users", new JsonArray()
                  .add(userOb))
                .put("totalRecords", 1);
        context.response()
                .setStatusCode(200)
                .end(responseOb.encode());

      } else if(query.equals("username==saruman")) {
        JsonObject userOb = new JsonObject()
                .put("username", "saruman")
                .put("id", sarumanId)
                .put("active", false);
        JsonObject responseOb = new JsonObject()
                .put("users", new JsonArray()
                  .add(userOb))
                .put("totalRecords", 1);
        context.response()
                .setStatusCode(200)
                .end(responseOb.encode());
      }
      else {
        context.response()
                .setStatusCode(404)
                .end("Not found");
      }
    } catch(Exception e) {
        context.response()
                .setStatusCode(500)
                .end("Error");
    }
  }

  private void handleToken(RoutingContext context) {
    context.response()
            .setStatusCode(200)
            .end("dummytoken");
  }
}


