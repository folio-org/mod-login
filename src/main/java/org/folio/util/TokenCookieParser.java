package org.folio.util;

import org.folio.rest.impl.LoginAPI;

public class TokenCookieParser {
  public String accessToken;
  public String refreshToken;

  public TokenCookieParser(String cookieHeader) {
    accessToken = getCookieValue(cookieHeader, LoginAPI.ACCESS_TOKEN);
    refreshToken = getCookieValue(cookieHeader, LoginAPI.REFRESH_TOKEN);
  }

  private String getCookieValue(String cookieHeader, String key) {
    String[] cookies = cookieHeader.split(";");
    for (String c : cookies) {
      String[] cookeNameValue = c.trim().split("=", 2);
      String cookieName = cookeNameValue[0].trim();
      String cookieValue = cookeNameValue[1].trim();
      if (cookieName.equals(key)) {
        return cookieValue;
      }
    }
    throw new RuntimeException("No cookie found for key " + key);
  }
}
