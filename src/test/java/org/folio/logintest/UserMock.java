package org.folio.logintest;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import static java.lang.Thread.sleep;
import static org.folio.util.LoginAttemptsHelper.LOGIN_ATTEMPTS_CODE;
import static org.folio.util.LoginAttemptsHelper.LOGIN_ATTEMPTS_TIMEOUT_CODE;

/**
 *
 * @author kurt
 */
public class UserMock extends AbstractVerticle {
  
  public static final String gollumId = "bc6e4932-6415-40e2-ac1e-67ecdd665366";
  public static final String bombadilId = "35bbcda7-866a-4231-b478-59b9dd2eb3ee";
  public static final String sarumanId = "340bafb8-ea74-4f51-be8c-ec6493fd517e";
  public static final String adminId = "8bd684c1-bbc3-4cf1-bcf4-8013d02a94ce";

  private JsonObject admin = new JsonObject()
    .put("username", "admin")
    .put("id", adminId)
    .put("active", true);
  private JsonObject responseAdmin = new JsonObject()
    .put("users", new JsonArray()
      .add(admin))
    .put("totalRecords", 1);

  public void start(Future<Void> future) {
    final int port = context.config().getInteger("port");

    Router router = Router.router(vertx);
    HttpServer server = vertx.createHttpServer();

    router.route("/users").handler(this::handleUsers);
    router.put("/users/"+adminId).handler(this::handleUserPut);
    router.route("/token").handler(this::handleToken);
    router.route("/configurations/entries").handler(this::handleConfig);
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
      } else if(query.equals("id=="+sarumanId)) {
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
      } else if(query.equals("id=="+gollumId)) {
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
      } else if (query.equals("username==admin")) {
        context.response()
          .setStatusCode(200)
          .end(responseAdmin.encode());
      } else {
        context.response()
          .setStatusCode(404)
          .end("Not found");
      }
    } catch (Exception e) {
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

  private void handleConfig(RoutingContext context) {
    try {
      JsonObject responseJson = new JsonObject();
      String queryString = "active==true&module==LOGIN&code==";
      String query = context.request().getParam("query");
      if (query.equals(queryString + LOGIN_ATTEMPTS_CODE)) {
        JsonObject configOb = new JsonObject()
          .put("value", 1);
        JsonArray array = new JsonArray();
        array.add(configOb);
        responseJson.put("configs", array)
          .put("totalRecords", 1);
        context.response()
          .setStatusCode(200)
          .end(responseJson.encode());
      } else if (query.equals(queryString + LOGIN_ATTEMPTS_TIMEOUT_CODE)) {
        JsonObject configOb = new JsonObject()
          .put("value", 1);
        JsonArray array = new JsonArray();
        array.add(configOb);
        responseJson.put("configs", array)
          .put("totalRecords", 1);
        context.response()
          .setStatusCode(200)
          .end(responseJson.encode());
      } else {
        context.response()
          .setStatusCode(404)
          .end("Not found");
      }
    } catch (Exception e) {
      context.response()
        .setStatusCode(500)
        .end("Error");
    }
  }

  private void handleUserPut(RoutingContext context) {
    admin.put("active", false);
    context.response()
      .setStatusCode(204)
      .end();
  }
}


