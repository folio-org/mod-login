package org.folio.util;

import java.util.Map;

import io.vertx.core.http.CookieSameSite;

public class CookieSameSiteConfig {
  private static CookieSameSite cookieSameSite = valueOf(System.getenv());

  public static CookieSameSite get() {
    return cookieSameSite;
  }

  /**
   * Setter for unit tests.
   */
  public static void set(Map<String,String> env) {
    cookieSameSite = valueOf(env);
  }

  private static CookieSameSite valueOf(Map<String,String> env) {
    var label = env.get("LOGIN_COOKIE_SAMESITE");
    if (label == null) {
      return CookieSameSite.LAX;
    }
    for (var value : CookieSameSite.values()) {
      if (value.toString().equals(label)) {
        return value;
      }
    }
    throw new IllegalArgumentException("SAML_COOKIE_SAMESITE environment variable must be "
      + "Strict, Lax or None, but found: " + label);
  }
}
