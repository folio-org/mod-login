package org.folio.logintest;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.CompositeFuture;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunnerWithParametersFactory;
import org.awaitility.Awaitility;
import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.RestVerticle;
import org.folio.rest.impl.LoginAPI;
import org.folio.rest.impl.TenantAPI;
import org.folio.rest.impl.TenantRefAPI;
import org.folio.rest.jaxrs.model.Config;
import org.folio.rest.jaxrs.model.Configurations;
import org.folio.rest.jaxrs.model.Credential;
import org.folio.rest.jaxrs.model.LogEvent;
import org.folio.rest.jaxrs.model.LoginCredentials;
import org.folio.rest.jaxrs.model.PasswordCreate;
import org.folio.rest.jaxrs.model.PasswordReset;
import org.folio.rest.jaxrs.model.UpdateCredentials;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.folio.util.AuthUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.folio.rest.jaxrs.model.TenantAttributes;

import static org.folio.util.LoginConfigUtils.EVENT_LOG_API_MODULE;

@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(VertxUnitRunnerWithParametersFactory.class)
public class EventsLoggingTests {

  private Vertx vertx;
  private int port;
  private RequestSpecification spec;

  private static final String USER_ID = "664feaf2-e57a-4d71-b4b7-d66cde654043";
  private static final String NEW_USER_ID = "a1d5f216-22f8-46e3-972e-70b6717f35db";
  private static final String CREATE_PASSWORD_ACTION_ID = "01a2dcf3-05d9-4190-94eb-b0114f90e7f8";
  private static final String RESET_PASSWORD_ACTION_ID = "52dd4d0a-fa15-45e5-a21a-0a964a76ce1d";
  private static final String PASSWORD = "password";
  private static final String NEW_PASSWORD = "Admin!10";
  private static final String TENANT = "tenant";
  private static final String TOKEN = "token";
  private static final String CLIENT_IP = "50.185.56.89";

  private String clientIpHeader;

  @Parameterized.Parameters
  public static Iterable<String> headers() {
    return Arrays.asList(LoginAPI.X_FORWARDED_FOR_HEADER, LoginAPI.OKAPI_REQUEST_IP_HEADER);
  }

  public EventsLoggingTests(String clientIpHeader) {
    this.clientIpHeader = clientIpHeader;
  }

  @Rule
  public WireMockRule mockServer = new WireMockRule(
    WireMockConfiguration.wireMockConfig()
      .dynamicPort());

  @Before
  public void setup(TestContext context) {
    port = NetworkUtils.nextFreePort();
    vertx = Vertx.vertx();

    try {
      PostgresClient.setPostgresTester(new PostgresTesterContainer());
      PostgresClient.getInstance(vertx);
    } catch (Exception e) {
      context.fail(e);
    }

    spec = new RequestSpecBuilder()
      .setBaseUri("http://localhost:" + port)
      .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
      .addHeader(RestVerticle.OKAPI_HEADER_TENANT, TENANT)
      .addHeader(RestVerticle.OKAPI_HEADER_TOKEN, TOKEN)
      .addHeader(LoginAPI.OKAPI_URL_HEADER, "http://localhost:" + mockServer.port())
      .addHeader(clientIpHeader, CLIENT_IP)
      .addHeader(LoginAPI.OKAPI_REQUEST_TIMESTAMP_HEADER, String.valueOf(new Date().getTime()))
      .build();

    mockHttpCalls();

    deployVerticle()
      .compose(v -> postTenant())
      .compose(v -> persistCredentials())
      .compose(v -> persistPasswordResetActions())
      .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void testSuccessfulLoginAttempt() {
    LoginCredentials body = new LoginCredentials();
    body.setUserId(USER_ID);
    body.setPassword(PASSWORD);

    RestAssured
      .given()
      .spec(spec)
      .body(JsonObject.mapFrom(body).encode())
      .when()
      .post("/authn/login")
      .then()
      .statusCode(201);

    Awaitility
      .await()
      .atMost(5, TimeUnit.SECONDS)
      .until(isEventSuccessfullyLogged(LogEvent.EventType.SUCCESSFUL_LOGIN_ATTEMPT, USER_ID));
  }

  @Test
  public void testFailedLoginAttempt() {
    LoginCredentials body = new LoginCredentials();
    body.setUserId(USER_ID);
    body.setPassword("incorrect");

    RestAssured
      .given()
      .spec(spec)
      .body(JsonObject.mapFrom(body).encode())
      .when()
      .post("/authn/login")
      .then()
      .statusCode(422);

    Awaitility
      .await()
      .atMost(5, TimeUnit.SECONDS)
      .until(isEventSuccessfullyLogged(LogEvent.EventType.FAILED_LOGIN_ATTEMPT, USER_ID));
  }

  @Test
  public void testUserBlock() {
    LoginCredentials body = new LoginCredentials();
    body.setUserId(USER_ID);
    body.setPassword("incorrect");

    //after 5 failed login attempts user must be blocked
    IntStream.range(0, 5)
      .forEach(i -> RestAssured
        .given()
        .spec(spec)
        .body(JsonObject.mapFrom(body).encode())
        .when()
        .post("/authn/login")
        .then()
        .statusCode(422));

    Awaitility
      .await()
      .atMost(5, TimeUnit.SECONDS)
      .until(isEventSuccessfullyLogged(LogEvent.EventType.USER_BLOCK, USER_ID));
  }

  @Test
  public void testPasswordChange() {
    UpdateCredentials body = new UpdateCredentials();
    body.setUserId(USER_ID);
    body.setPassword(PASSWORD);
    body.setNewPassword(NEW_PASSWORD);

    RestAssured
      .given()
      .spec(spec)
      .body(JsonObject.mapFrom(body).encode())
      .when()
      .post("/authn/update")
      .then()
      .statusCode(204);

    Awaitility
      .await()
      .atMost(5, TimeUnit.SECONDS)
      .until(isEventSuccessfullyLogged(LogEvent.EventType.PASSWORD_CHANGE, USER_ID));
  }

  @Test
  public void testPasswordCreate() {
    PasswordReset body = new PasswordReset();
    body.setPasswordResetActionId(CREATE_PASSWORD_ACTION_ID);
    body.setNewPassword(NEW_PASSWORD);

    RestAssured
      .given()
      .spec(spec)
      .body(JsonObject.mapFrom(body).encode())
      .when()
      .post("/authn/reset-password")
      .then()
      .statusCode(201);

    Awaitility
      .await()
      .atMost(5, TimeUnit.SECONDS)
      .until(isEventSuccessfullyLogged(LogEvent.EventType.PASSWORD_CREATE, NEW_USER_ID));
  }

  @Test
  public void testPasswordReset() {
    PasswordReset body = new PasswordReset();
    body.setPasswordResetActionId(RESET_PASSWORD_ACTION_ID);
    body.setNewPassword(NEW_PASSWORD);

    RestAssured
      .given()
      .spec(spec)
      .body(JsonObject.mapFrom(body).encode())
      .when()
      .post("/authn/reset-password")
      .then()
      .statusCode(201);

    Awaitility
      .await()
      .atMost(5, TimeUnit.SECONDS)
      .until(isEventSuccessfullyLogged(LogEvent.EventType.PASSWORD_RESET, USER_ID));
  }

  private void mockHttpCalls() {
    JsonObject user = new JsonObject()
      .put("id", USER_ID)
      .put("active", true);
    JsonObject users = new JsonObject()
      .put("totalRecords", 1)
      .put("users", new JsonArray().add(user));

    mockServer.stubFor(
      WireMock.get(WireMock.urlPathEqualTo("/users"))
        .willReturn(WireMock.okJson(users.encode()))
    );

    mockServer.stubFor(
      WireMock.put("/users/" + USER_ID)
        .willReturn(WireMock.noContent())
    );

    List<Config> configs = new ArrayList<>();
    configs.add(new Config()
      .withModule(EVENT_LOG_API_MODULE)
      .withCode("STATUS")
      .withEnabled(true));
    configs.add(new Config()
      .withModule(EVENT_LOG_API_MODULE)
      .withCode(LogEvent.EventType.SUCCESSFUL_LOGIN_ATTEMPT.toString())
      .withEnabled(true));
    configs.add(new Config()
      .withModule(EVENT_LOG_API_MODULE)
      .withCode(LogEvent.EventType.FAILED_LOGIN_ATTEMPT.toString())
      .withEnabled(true));
    configs.add(new Config()
      .withModule(EVENT_LOG_API_MODULE)
      .withCode(LogEvent.EventType.USER_BLOCK.toString())
      .withEnabled(true));
    configs.add(new Config()
      .withModule(EVENT_LOG_API_MODULE)
      .withCode(LogEvent.EventType.PASSWORD_CHANGE.toString())
      .withEnabled(true));
    configs.add(new Config()
      .withModule(EVENT_LOG_API_MODULE)
      .withCode(LogEvent.EventType.PASSWORD_CREATE.toString())
      .withEnabled(true));
    configs.add(new Config()
      .withModule(EVENT_LOG_API_MODULE)
      .withCode(LogEvent.EventType.PASSWORD_RESET.toString())
      .withEnabled(true));

    Configurations configurations = new Configurations();
    configurations.setTotalRecords(7);
    configurations.setConfigs(configs);

    mockServer.stubFor(
      WireMock.get(WireMock.urlPathEqualTo("/configurations/entries"))
        .withQueryParam("query", WireMock.equalTo("module==EVENT_LOG"))
        .willReturn(WireMock.okJson(JsonObject.mapFrom(configurations).encode()))
    );

    mockServer.stubFor(
      WireMock.get(WireMock.urlPathEqualTo("/configurations/entries"))
        .withQueryParam("query", WireMock.equalTo("configName==password.history.number"))
        .willReturn(WireMock.okJson(new JsonObject()
          .put("totalRecords", 1)
          .put("configs", new JsonArray().add(new JsonObject().put("value", 10)))
          .encode()))
    );

    mockServer.stubFor(
      WireMock.get(WireMock.urlPathEqualTo("/configurations/entries"))
        .withQueryParam("query", WireMock.equalTo("code==login.fail.to.warn.attempts"))
        .willReturn(WireMock.okJson(new JsonObject()
          .put("totalRecords", 1)
          .put("configs", new JsonArray().add(new JsonObject().put("value", 3)))
          .encode()))
    );

    mockServer.stubFor(
      WireMock.get(WireMock.urlPathEqualTo("/configurations/entries"))
        .withQueryParam("query", WireMock.equalTo("code==login.fail.attempts"))
        .willReturn(WireMock.okJson(new JsonObject()
          .put("totalRecords", 1)
          .put("configs", new JsonArray().add(new JsonObject().put("value", 5)))
          .encode()))
    );

    mockServer.stubFor(
      WireMock.get(WireMock.urlPathEqualTo("/configurations/entries"))
        .withQueryParam("query", WireMock.equalTo("code==login.fail.timeout"))
        .willReturn(WireMock.okJson(new JsonObject()
          .put("totalRecords", 1)
          .put("configs", new JsonArray().add(new JsonObject().put("value", 10)))
          .encode()))
    );

    mockServer.stubFor(
      WireMock.post("/token")
        .willReturn(WireMock.ok().withHeader(RestVerticle.OKAPI_HEADER_TOKEN, TOKEN))
    );

    mockServer.stubFor(
      WireMock.post("/refreshtoken")
        .willReturn(WireMock.okJson(new JsonObject().put("refreshToken", "dummyrefreshtoken").encode())
          .withStatus(201))
    );
  }

  private Future<String> deployVerticle() {
    DeploymentOptions options = new DeploymentOptions()
      .setConfig(new JsonObject().put("http.port", port));
    return vertx.deployVerticle(RestVerticle.class.getName(), options);
  }

  private Future<Void> postTenant() {
    Promise<Void> promise = Promise.promise();
    TenantAttributes ta = new TenantAttributes().withModuleTo("mod-login-1.1.0");
    TenantAPI tenantAPI = new TenantRefAPI();
    Map<String, String> okapiHeaders = Map.of("x-okapi-url", "http://localhost:" + port,
        "x-okapi-tenant", TENANT);
    tenantAPI.postTenantSync(ta, okapiHeaders, handler -> promise.complete(),
        vertx.getOrCreateContext());
    return promise.future();
  }

  private Future<String> persistCredentials() {
    Promise<String> promise = Promise.promise();
    AuthUtil authUtil = new AuthUtil();
    String salt = authUtil.getSalt();
    String id = UUID.randomUUID().toString();
    Credential cred = new Credential()
      .withId(id)
      .withSalt(salt)
      .withHash(authUtil.calculateHash(PASSWORD, salt))
      .withUserId(USER_ID);

    PostgresClient pgClient = PostgresClient.getInstance(vertx, TENANT);
    pgClient.save("auth_credentials", id, cred, promise);

    return promise.future();
  }

  private CompositeFuture persistPasswordResetActions() {
    Promise<String> existingUserPromise = Promise.promise();
    Promise<String> newUserPromise = Promise.promise();

    PostgresClient pgClient = PostgresClient.getInstance(vertx, TENANT);

    PasswordCreate existingUserAction = new PasswordCreate();
    existingUserAction.setId(RESET_PASSWORD_ACTION_ID);
    existingUserAction.setUserId(USER_ID);
    existingUserAction.setExpirationTime(Date.from(Instant.now().plus(1, ChronoUnit.DAYS)));
    pgClient.save("auth_password_action", RESET_PASSWORD_ACTION_ID, existingUserAction, existingUserPromise);

    PasswordCreate newUserAction = new PasswordCreate();
    newUserAction.setId(CREATE_PASSWORD_ACTION_ID);
    newUserAction.setUserId(NEW_USER_ID);
    newUserAction.setExpirationTime(Date.from(Instant.now().plus(1, ChronoUnit.DAYS)));
    pgClient.save("auth_password_action", CREATE_PASSWORD_ACTION_ID, newUserAction, newUserPromise);

    return CompositeFuture.all(existingUserPromise.future(), newUserPromise.future());
  }

  private Callable<Boolean> isEventSuccessfullyLogged(LogEvent.EventType eventType, String userId) {

    return () -> {
      CompletableFuture<Boolean> future = new CompletableFuture<>();
      PostgresClient.getInstance(vertx, TENANT).get("event_logs", LogEvent.class, new Criterion(), false, get ->
        future.complete(get.result()
          .getResults()
          .stream()
          .anyMatch(event -> event.getEventType() == eventType
            && event.getTenant().equals(TENANT)
            && event.getUserId().equals(userId)
            && event.getIp().equals(CLIENT_IP)
            && event.getBrowserInformation() != null
            && event.getTimestamp() != null
          )));

      return future.get(1, TimeUnit.SECONDS);
    };
  }
}
