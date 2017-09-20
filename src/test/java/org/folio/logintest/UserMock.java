package org.folio.logintest;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 *
 * @author kurt
 */
public class UserMock extends AbstractVerticle {
  
  public void start(Future<Void> future) {
    //final String portStr = context.config().getString("port");
    //final String portStr = System.getProperty("port", defaultPort);
    //final int port = Integer.parseInt(portStr);
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
                .put("id", "bc6e4932-6415-40e2-ac1e-67ecdd665366")
                .put("active", true);
        JsonObject responseOb = new JsonObject()
                .put("users", new JsonArray()
                  .add(userOb))
                .put("totalRecords", 1);
        context.response()
                .setStatusCode(200)
                .end(responseOb.encode());
                
      } else {
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


