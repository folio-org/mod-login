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

    refreshToken = getCookieValue(cookies, LoginAPI.FOLIO_REFRESH_TOKEN);

    if (containsAccessToken(cookies)) {
      accessToken = getCookieValue(cookies, LoginAPI.FOLIO_ACCESS_TOKEN);
    }
  }

  private boolean containsAccessToken(List<Cookie> cookies) {
    return cookies.stream().anyMatch(cookie -> cookie.name().equals(LoginAPI.FOLIO_ACCESS_TOKEN));
  }

  private String getCookieValue(List<Cookie> cookies, String name) {
    String value = null;

    for (var cookie : cookies) {
      if (!name.equals(cookie.name())) {
        continue;
      }
      if (value != null) {
        throw new IllegalArgumentException("Duplicate cookie for name " + name);
      }
      logger.debug("Reached cookie.value");
      value = cookie.value();
    }

    if (value == null) {
      throw new IllegalArgumentException("No cookie found for name: " + name);
    }
    return value;
  }
}
