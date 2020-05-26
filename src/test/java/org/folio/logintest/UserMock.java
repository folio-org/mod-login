package org.folio.logintest;

import static java.lang.Thread.sleep;
import static org.folio.util.LoginAttemptsHelper.LOGIN_ATTEMPTS_CODE;
import static org.folio.util.LoginAttemptsHelper.LOGIN_ATTEMPTS_TIMEOUT_CODE;

import java.util.UUID;

import org.drools.core.spi.Accumulator.SafeAccumulator;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * @author kurt
 */
public class UserMock extends AbstractVerticle {

  public static final String gollumId = "bc6e4932-6415-40e2-ac1e-67ecdd665366";
  public static final String bombadilId = "35bbcda7-866a-4231-b478-59b9dd2eb3ee";
  public static final String sarumanId = "340bafb8-ea74-4f51-be8c-ec6493fd517e";
  public static final String gimliId = "ade0c47f-4c86-46e1-8932-0991322799c1";
  private static final String adminId = "8bd684c1-bbc3-4cf1-bcf4-8013d02a94ce";

  private JsonObject admin = new JsonObject()
    .put("username", "admin")
    .put("id", adminId)
    .put("active", true);
  private JsonObject responseAdmin = new JsonObject()
    .put("users", new JsonArray()
      .add(admin))
    .put("totalRecords", 1);

  public void start(Promise<Void> promise) {
    final int port = context.config().getInteger("port");

    Router router = Router.router(vertx);
    HttpServer server = vertx.createHttpServer();

    router.route("/users").handler(this::handleUsers);
    router.put("/users/:id").handler(this::handleUserPut);
    router.route("/token").handler(this::handleToken);
    router.route("/refreshtoken").handler(this::handleRefreshToken);
    router.route("/configurations/entries").handler(this::handleConfig);
    System.out.println("Running UserMock on port " + port);
    server.requestHandler(router::handle).listen(port, result -> {
      if (result.failed()) {
        promise.fail(result.cause());
      } else {
        promise.complete();
      }
    });
  }

  private void handleUsers(RoutingContext context) {
    try {
      String query = context.request().getParam("query");
      JsonObject userOb;
      JsonObject responseOb;
      switch (query) {
        case "username==gollum":
          userOb = new JsonObject()
            .put("username", "gollum")
            .put("id", gollumId)
            .put("active", true);
          responseOb = new JsonObject()
            .put("users", new JsonArray()
              .add(userOb))
            .put("totalRecords", 1);
          context.response()
            .setStatusCode(200)
            .end(responseOb.encode());
          break;
        case "username==bombadil":
          sleep(500); //Bombadil gets delayed on purpose

          userOb = new JsonObject()
            .put("username", "bombadil")
            .put("id", bombadilId)
            .put("active", true);
          responseOb = new JsonObject()
            .put("users", new JsonArray()
              .add(userOb))
            .put("totalRecords", 1);
          context.response()
            .setStatusCode(200)
            .end(responseOb.encode());
          break;
        case "username==gimli":
          userOb = new JsonObject();
          context.response()
            .setStatusCode(200)
            .end(userOb.encode());
          break;
        case "username==mrunderhill":
          userOb = new JsonObject();
          responseOb = new JsonObject()
            .put("users", new JsonArray()
              .add(userOb))
            .put("totalRecords", 0);
          context.response()
            .setStatusCode(200)
            .end(responseOb.encode());
        case "username==gandalf":
          responseOb = new JsonObject()
            .put("users", new JsonArray()
              .add(new JsonObject()
                  .put("username", "gandalf")
                  .put("id", UUID.randomUUID().toString())
                  .put("active", true))
              .add(new JsonObject()
                  .put("username", "gandalf")
                  .put("id", UUID.randomUUID().toString())
                  .put("active", false)))
            .put("totalRecords", 2);
          context.response()
            .setStatusCode(200)
            .end(responseOb.encode());
          break;
        case "username==strider":
          userOb = new JsonObject()
            .put("username", "strider")
            .put("active", true);
          responseOb = new JsonObject()
            .put("users", new JsonArray()
              .add(userOb))
            .put("totalRecords", 1);
          context.response()
            .setStatusCode(200)
            .end(responseOb.encode());
          break;
        case "username==saruman":
          userOb = new JsonObject()
            .put("username", "saruman")
            .put("id", sarumanId)
            .put("active", false);
          responseOb = new JsonObject()
            .put("users", new JsonArray()
              .add(userOb))
            .put("totalRecords", 1);
          context.response()
            .setStatusCode(200)
            .end(responseOb.encode());
          break;
        case "id==" + sarumanId:
          userOb = new JsonObject()
            .put("username", "saruman")
            .put("id", sarumanId)
            .put("active", false);
          responseOb = new JsonObject()
            .put("users", new JsonArray()
              .add(userOb))
            .put("totalRecords", 1);
          context.response()
            .setStatusCode(200)
            .end(responseOb.encode());
          break;
        case "id==" + gollumId:
          userOb = new JsonObject()
            .put("username", "gollum")
            .put("id", gollumId)
            .put("active", true);
          responseOb = new JsonObject()
            .put("users", new JsonArray()
              .add(userOb))
            .put("totalRecords", 1);
          context.response()
            .setStatusCode(200)
            .end(responseOb.encode());
          break;
        case "username==admin":
          context.response()
            .setStatusCode(200)
            .end(responseAdmin.encode());
          break;
        case "id==" + adminId:
          context.response()
            .setStatusCode(200)
            .end(responseAdmin.encode());
          break;
        default:
          context.response()
            .setStatusCode(404)
            .end("Not found");
          break;
      }
    } catch (Exception e) {
      context.response()
        .setStatusCode(500)
        .end("Error");
    }
  }

  private void handleToken(RoutingContext context) {
    context.response()
      .setStatusCode(201)
      .putHeader("X-Okapi-Token", "dummytoken")
      .end(new JsonObject().put("token", "dummytoken").encode());
  }

  private void handleRefreshToken(RoutingContext context) {
    context.response()
      .setStatusCode(201)
      .end(new JsonObject().put("refreshToken", "dummyrefreshtoken").encode());
  }

  private void handleConfig(RoutingContext context) {
    try {
      JsonObject responseJson = new JsonObject();
      String queryString = "code==";
      String query = context.request().getParam("query");
      if (query.equals(queryString + LOGIN_ATTEMPTS_CODE)) {
        JsonObject configOb = new JsonObject()
          .put("value", 2);
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
    String id = context.request().getParam("id");
    if(id.equals(adminId)) {
      admin.put("active", false);
      context.response()
        .setStatusCode(204)
        .end();
    } else {
      context.response()
        .setStatusCode(204)
        .end();
    }
  }
}


