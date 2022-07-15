package org.folio.util;

import java.util.List;

import org.folio.rest.impl.LoginAPI;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

public class TokenCookieParser {
  private static final Logger logger = LogManager.getLogger(TokenCookieParser.class);

  public String getAccessToken() {
    return accessToken;
  }
  private String accessToken;

  public String getRefreshToken() {
    return refreshToken;
  }
  private String refreshToken;

  public TokenCookieParser(String cookieHeader) {
    logger.debug("Cookie header received by parser {}", cookieHeader);

    List<Cookie> cookies = ServerCookieDecoder.STRICT.decodeAll(cookieHeader.trim());

    accessToken = getCookieValue(cookies, LoginAPI.ACCESS_TOKEN);
    refreshToken = getCookieValue(cookies, LoginAPI.REFRESH_TOKEN);
  }

  private String getCookieValue(List<Cookie> cookies, String name) {
    if (cookieNameOccursMoreThanOnce(cookies, name)) {
      throw new IllegalArgumentException("Cookie name occurs more than once: " + name);
    }

    for (Cookie c : cookies) {
      if (c.name().equals(name)) {
        return c.value();
      }
    }

    throw new IllegalArgumentException("No cookie found for name: " + name);
  }

  private boolean cookieNameOccursMoreThanOnce(List<Cookie> cookies, String cookieName) {
    int count = 0;

    for (Cookie c : cookies) {
      if (cookieName.equals(c.name().trim())) {
        count++;
      }
    }

    return count > 1;
  }
}
