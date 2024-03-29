package org.folio.logintest;

import static java.lang.Thread.sleep;
import static org.folio.util.LoginAttemptsHelper.LOGIN_ATTEMPTS_CODE;
import static org.folio.util.LoginAttemptsHelper.LOGIN_ATTEMPTS_TIMEOUT_CODE;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.XOkapiHeaders;

/**
 * @author kurt
 */
public class Mocks extends AbstractVerticle {
  private static final Logger logger = LogManager.getLogger(Mocks.class);

  public static final String gollumId = "bc6e4932-6415-40e2-ac1e-67ecdd665366";
  public static final String bombadilId = "35bbcda7-866a-4231-b478-59b9dd2eb3ee";
  public static final String sarumanId = "340bafb8-ea74-4f51-be8c-ec6493fd517e";
  public static final JsonObject credsObject1 = new JsonObject()
    .put("id", UUID.randomUUID().toString())
    .put("username", "gollum")
    .put("userId", gollumId)
    .put("password", "12345");
  public static final JsonObject credsObject2 = new JsonObject()
    .put("username", "gollum")
    .put("password", "12345");
  public static final JsonObject credsObject3 = new JsonObject()
    .put("username", "saruman")
    .put("userId", sarumanId)
    .put("password", "12345");
  public static final JsonObject credsObject4 = new JsonObject()
    .put("username", "gollum")
    .put("password", "54321");
  public static final JsonObject credsObject5 = new JsonObject()
    .put("userId", gollumId)
    .put("password", "54321");
  public static final JsonObject credsObject6 = new JsonObject()
    .put("username", "gollum")
    .put("password", "12345")
    .put("newPassword", "54321");
  public static final JsonObject credsNoUsernameOrUserId = new JsonObject()
      .put("password", "12345");
  public static final JsonObject credsNoPassword = new JsonObject()
      .put("username", "gollum");
  public static final JsonObject credsElicitEmptyUserResp = new JsonObject()
      .put("username", "mrunderhill")
      .put("password", "54321");
  public static final JsonObject credsElicitBadUserResp = new JsonObject()
      .put("username", "gimli")
      .put("password", "54321");
  public static final JsonObject credsNonExistentUser = new JsonObject()
      .put("username", "mickeymouse")
      .put("password", "54321");
  public static final JsonObject credsElicitMultiUserResp = new JsonObject()
      .put("username", "gandalf")
      .put("password", "54321");
  public static final JsonObject credsUserWithNoId = new JsonObject()
      .put("username", "strider")
      .put("password", "54321");

  public static final String REFRESH_TOKEN = "dummyrefreshtoken";
  public static final String ACCESS_TOKEN = "dummyaccesstoken";
  public static final int REFRESH_TOKEN_EXPIRATION = 604800;
  public static final int ACCESS_TOKEN_EXPIRATION = 600;
  public static final String TOKEN_FETCH_ERROR_STATUS = "tokenShouldReturn404";
  public static final String TOKEN_FETCH_SHOULD_RETURN_500 = "tokenShouldReturn500";

  private static final String adminId = "8bd684c1-bbc3-4cf1-bcf4-8013d02a94ce";
  private static final String userWithSingleTenantId = "08b9e1c4-a0b2-4c64-8d57-2e18784ac7fe";
  private static final String userWithMultipleTenantsId1 = "01e683a0-6a07-4c3f-81fd-3355feddc884";
  private static final String userWithMultipleTenantsId2 = "e72968cd-062e-4625-936f-8dc9c523b359";
  private static final String TENANT_DIKU = "diku";
  private static final String TENANT_OTHER = "other";


  private static ConcurrentHashMap<String,JsonObject> configs = new ConcurrentHashMap<>();
  private JsonObject admin = new JsonObject()
    .put("username", "admin")
    .put("id", adminId)
    .put("active", true);
  private JsonObject responseAdmin = new JsonObject()
    .put("users", new JsonArray()
      .add(admin))
    .put("totalRecords", 1);

  public void start(Promise<Void> promise) {
    resetConfigs();
    final int port = context.config().getInteger("port");

    Router router = Router.router(vertx);
    HttpServer server = vertx.createHttpServer();

    router.route("/users").handler(this::handleUsers);
    router.put("/users/:id").handler(this::handleUserPut);
    router.route("/token").handler(this::handleTokenLegacy);
    router.route("/token/sign").handler(this::handleTokenSign);
    router.route("/token/refresh").handler(this::handleTokenRefresh);
    router.route("/token/invalidate").handler(this::handleLogout);
    router.route("/token/invalidate-all").handler(this::handleLogoutAll);
    router.route("/configurations/entries").handler(this::handleConfig);
    router.route("/user-tenants").handler(this::handleUserTenants);
    logger.info("Running UserMock on port {}", port);
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
        case "username==\"gollum\"":
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
            .putHeader("Content-Type", "application/json")
            .end(responseOb.encode());
          break;
        case "username==\"bombadil\"":
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
            .putHeader("Content-Type", "application/json")
            .end(responseOb.encode());
          break;
        case "username==\"gimli\"":
          userOb = new JsonObject();
          context.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(userOb.encode());
          break;
        case "username==\"mrunderhill\"":
          responseOb = new JsonObject()
            .put("users", new JsonArray())
            .put("totalRecords", 0);
          context.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(responseOb.encode());
          break;
        case "username==\"gandalf\"":
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
            .putHeader("Content-Type", "application/json")
            .end(responseOb.encode());
          break;
        case "username==\"strider\"":
          userOb = new JsonObject()
            .put("username", "strider")
            .put("active", true);
          responseOb = new JsonObject()
            .put("users", new JsonArray()
              .add(userOb))
            .put("totalRecords", 1);
          context.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(responseOb.encode());
          break;
        case "username==\"saruman\"":
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
            .putHeader("Content-Type", "application/json")
            .end(responseOb.encode());
          break;
        case "id==\"" + sarumanId + "\"":
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
            .putHeader("Content-Type", "application/json")
            .end(responseOb.encode());
          break;
        case "id==\"" + gollumId + "\"":
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
            .putHeader("Content-Type", "application/json")
            .end(responseOb.encode());
          break;
        case "username==\"admin\"":
          context.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(responseAdmin.encode());
          break;
        case "id==\"" + adminId + "\"":
          context.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(responseAdmin.encode());
          break;
        case "username==\"single\"":
          userOb = new JsonObject()
            .put("username", "single")
            .put("id", userWithSingleTenantId)
            .put("active", true);
          responseOb = new JsonObject()
            .put("users", new JsonArray()
              .add(userOb))
            .put("totalRecords", 1);
          context.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(responseOb.encode());
          break;
        default:
          responseOb = new JsonObject()
            .put("users", new JsonArray())
            .put("totalRecords", 0);
          context.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(responseOb.encode());
          break;
      }
    } catch (Exception e) {
      context.response()
        .setStatusCode(500)
        .end("Error");
    }
  }

  private void returnTokens(RoutingContext context) {
    var atExpiration = Instant.now().plusSeconds(ACCESS_TOKEN_EXPIRATION).toString();
    var rtExpiration = Instant.now().plusSeconds(REFRESH_TOKEN_EXPIRATION).toString();
    var response = new JsonObject()
      .put("accessTokenExpiration", atExpiration)
      .put("refreshTokenExpiration", rtExpiration)
      .put("accessToken", ACCESS_TOKEN)
      .put("refreshToken", REFRESH_TOKEN);
    context.response()
      .setStatusCode(201)
      .putHeader("Content-Type", "application/json")
      .end(response.encode());
  }

  private void handleTokenRefresh(RoutingContext context) {
    returnTokens(context);
  }

  private void handleTokenSign(RoutingContext context) {
    returnTokens(context);
  }

  private void handleTokenLegacy(RoutingContext context) {
    if (System.getProperty(TOKEN_FETCH_ERROR_STATUS) != null) {
      context.response()
          .setStatusCode(Integer.parseInt(System.getProperty(TOKEN_FETCH_ERROR_STATUS)))
          .end("Error");
    }
    context.response()
      .setStatusCode(201)
      .putHeader("Content-Type", "application/json")
      .end(new JsonObject().put("token", "dummytoken").encode());
  }

  private void returnLogoutResponse(RoutingContext context) {
    if (context.request().headers().get(XOkapiHeaders.TOKEN).equals("expiredtoken")) {
      context.response()
      .setStatusCode(401)
      .end();
      return;
    }
    context.response()
      .setStatusCode(204)
      .end();
  }

  private void handleLogoutAll(RoutingContext context) {
    returnLogoutResponse(context);
  }

  private void handleLogout(RoutingContext context) {
    returnLogoutResponse(context);
  }

  public static void setConfig(String code, JsonObject json) {
    configs.put(code, json);
  }

  public static JsonObject removeConfig(String code) {
    return configs.remove(code);
  }

  public static void resetConfigs() {
    JsonObject configOb = new JsonObject().put("value", 2);
    JsonArray array = new JsonArray().add(configOb);
    JsonObject responseJson = new JsonObject()
        .put("configs", array)
        .put("totalRecords", 1);
    configs.put(LOGIN_ATTEMPTS_CODE, responseJson);

    configOb = new JsonObject().put("value", 2);
    array = new JsonArray().add(configOb);
    responseJson = new JsonObject()
       .put("configs", array)
       .put("totalRecords", 1);
    configs.put(LOGIN_ATTEMPTS_TIMEOUT_CODE, responseJson);
  }

  private void handleConfig(RoutingContext context) {
    try {
      JsonObject responseJson;
      String queryString = "code==";
      String query = context.request().getParam("query");
      if (query.equals(queryString + LOGIN_ATTEMPTS_CODE)) {
        responseJson = configs.get(LOGIN_ATTEMPTS_CODE);
      } else if (query.equals(queryString + LOGIN_ATTEMPTS_TIMEOUT_CODE)) {
        responseJson = configs.get(LOGIN_ATTEMPTS_TIMEOUT_CODE);
      } else {
        responseJson = new JsonObject()
          .put("configs", new JsonArray())
          .put("totalRecords", 0);
      }
      context.response()
        .setStatusCode(200)
        .putHeader("Content-Type", "application/json")
        .end(responseJson.encode());
    } catch (Exception e) {
      context.response()
        .setStatusCode(500)
        .end("Error");
    }
  }

  private void handleUserPut(RoutingContext context) {
    context.request().body().onSuccess(body -> {
      try {
        JsonObject userObject = new JsonObject(body);
        String id = context.request().getParam("id");
        if (!userObject.getString("id").equals(id)) {
          context.response()
            .setStatusCode(500)
            .end("user.id != param.id");
          return;
        }
        if (id.equals(adminId)) {
          admin.put("active", false);
        }
        context.response()
          .setStatusCode(204)
          .putHeader("Content-Type", "application/json")
          .end();
      } catch (Exception e) {
        context.response()
          .setStatusCode(500)
          .end(e.getMessage());
      }
    });
  }

  private void handleUserTenants(RoutingContext context) {
    String username = context.request().getParam("username");
    String userId = context.request().getParam("userId");
    String tenantId = context.request().getParam("tenantId");
    if ("admin".equals(username) && TENANT_OTHER.equals(tenantId)) {
      JsonObject userTenants = new JsonObject()
        .put("id", "id")
        .put("userId", adminId)
        .put("username", "admin")
        .put("tenantId", TENANT_OTHER);
      JsonObject response = new JsonObject()
        .put("userTenants", new JsonArray()
          .add(userTenants))
        .put("totalRecords", 1);
      context.response()
        .setStatusCode(200)
        .putHeader("Content-Type", "application/json")
        .end(response.encode());
    } else if ("missing".equals(username) && TENANT_OTHER.equals(tenantId)) {
      JsonObject response = new JsonObject()
        .put("userTenants", new JsonArray())
        .put("totalRecords", 0);
      context.response()
        .setStatusCode(200)
        .putHeader("Content-Type", "application/json")
        .end(response.encode());
    } else if ("admin".equals(username) && "missing".equals(tenantId)) {
      JsonObject response = new JsonObject()
        .put("userTenants", new JsonArray())
        .put("totalRecords", 0);
      context.response()
        .setStatusCode(200)
        .putHeader("Content-Type", "application/json")
        .end(response.encode());
    } else if (adminId.equals(userId) && TENANT_OTHER.equals(tenantId)) {
      JsonObject userTenants = new JsonObject()
        .put("id", "id")
        .put("userId", adminId)
        .put("username", "admin")
        .put("tenantId", TENANT_OTHER);
      JsonObject response = new JsonObject()
        .put("userTenants", new JsonArray()
          .add(userTenants))
        .put("totalRecords", 1);
      context.response()
        .setStatusCode(200)
        .putHeader("Content-Type", "application/json")
        .end(response.encode());
    } else if ("single".equals(username) && tenantId == null) {
      JsonObject userTenants = new JsonObject()
        .put("id", "id")
        .put("userId", userWithSingleTenantId)
        .put("username", username)
        .put("tenantId", TENANT_OTHER);
      JsonObject response = new JsonObject()
        .put("userTenants", new JsonArray()
          .add(userTenants))
        .put("totalRecords", 1);
      context.response()
        .setStatusCode(200)
        .putHeader("Content-Type", "application/json")
        .end(response.encode());
    } else if ("multiple".equals(username) && tenantId == null) {
      JsonObject ut1 = new JsonObject()
        .put("id", "id")
        .put("userId", userWithMultipleTenantsId1)
        .put("username", username)
        .put("tenantId", TENANT_OTHER);
      JsonObject ut2 = new JsonObject()
        .put("id", "id")
        .put("userId", userWithMultipleTenantsId2)
        .put("username", username)
        .put("tenantId", TENANT_DIKU);
      JsonObject response = new JsonObject()
        .put("userTenants", new JsonArray()
          .add(ut1)
          .add(ut2))
        .put("totalRecords", 2);
      context.response()
        .setStatusCode(200)
        .putHeader("Content-Type", "application/json")
        .end(response.encode());
    } else if (tenantId == null) {
      JsonObject userTenants = new JsonObject()
        .put("id", "id")
        .put("userId", userId)
        .put("username", username)
        .put("tenantId", TENANT_DIKU);
      JsonObject response = new JsonObject()
        .put("userTenants", new JsonArray()
          .add(userTenants))
        .put("totalRecords", 1);
      context.response()
        .setStatusCode(200)
        .putHeader("Content-Type", "application/json")
        .end(response.encode());
    } else {
      throw new RuntimeException("unknown test values");
    }
  }}

