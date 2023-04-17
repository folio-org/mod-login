package org.folio.services;

import io.vertx.codegen.annotations.Fluent;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.folio.rest.jaxrs.model.UserTenant;
import org.folio.services.impl.UserServiceImpl;

/**
 * This service provides access to the mod-users API
 */
@ProxyGen
public interface UserService {

  static UserService create(Vertx vertx) {
    return new UserServiceImpl(vertx);
  }

  /**
   * Creates proxy instance that helps to push message into the message queue
   *
   * @param vertx   vertx instance
   * @param address host address
   * @return LogStorageService instance
   */
  static UserService createProxy(Vertx vertx, String address) {
    return new UserServiceVertxEBProxy(vertx, address);
  }

  /**
   * Query the mod-users module to determine if the username is valid for login
   *
   * @param username           username possibly used for login
   * @param userId             user identifier possibly used for login
   * @param tenant             effective tenant
   * @param okapiURL           Okapi URL
   * @param requestToken       request token
   * @return asyncResult with user as a JsonObject
   */
  @Fluent
  UserService lookupUser(String username, String userId, String tenant, final String okapiURL,
      String requestToken, Handler<AsyncResult<JsonObject>> asyncResultHandler);

  /**
   * Retrieves the User-Tenant record matching the given username or user id, and tenant id.
   * Returns a 404 if none matches.
   *
   * @param currentTenantId    identifier of the tenant being used in the login request
   * @param username           username possibly used for login
   * @param userId             user identifier possibly used for login
   * @param requestedTenantId  identifier of the tenant requested by the user
   * @param headers            okapi headers
   * @return asyncResult with the response entity {@link UserTenant} as a JsonObject
   */
  @Fluent
  UserService getUserTenant(String currentTenantId, String username, String userId, String requestedTenantId,
      JsonObject headers, Handler<AsyncResult<JsonObject>> asyncResultHandler);

}
