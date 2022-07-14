package org.folio.util;

import org.apache.commons.lang3.StringUtils;
import org.folio.rest.impl.LoginAPI;

public class TokenCookieParser {
  public String getAccessToken() {
    return accessToken;
  }
  private String accessToken;

  public String getRefreshToken() {
    return refreshToken;
  }
  private String refreshToken;

  public TokenCookieParser(String cookieHeader) {
    accessToken = getCookieValue(cookieHeader, LoginAPI.ACCESS_TOKEN);
    refreshToken = getCookieValue(cookieHeader, LoginAPI.REFRESH_TOKEN);
  }

  private String getCookieValue(String cookieHeader, String name) {
    String headerStripped = StringUtils.strip(cookieHeader, "\t");
    String[] cookies = headerStripped.split(";");

    if (cookieNameOccursMoreThanOnce(cookies, name)) {
      throw new IllegalArgumentException("Cookie name occurs more than once: " + name);
    }

    for (String c : cookies) {
      String[] cookeNameValue = getCookieNameValue(c);
      String cookieName = cookeNameValue[0].trim();
      String cookieValue = cookeNameValue[1].trim();
      if (cookieName.equals(name)) {
        return cookieValue;
      }
    }

    throw new IllegalArgumentException("No cookie found for name: " + name);
  }

  private String[] getCookieNameValue(String c) {
    return c.split("=", 2);
  }

  private boolean cookieNameOccursMoreThanOnce(String[] cookies, String cookieName) {
    int count = 0;

    for (String c : cookies) {
      String[] cookeNameValue = getCookieNameValue(c);
      if (cookieName.equals(cookeNameValue[0])) {
        count++;
      }
    }

    return count > 1;
  }
}
