package org.folio.logintest;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;

import org.folio.rest.impl.LoginAPI;
import org.folio.util.TokenCookieParser;

public class TokenCookieParserTest {
  @Test
  public void testParseCookie() {
    var header = LoginAPI.FOLIO_REFRESH_TOKEN + "=123;" + LoginAPI.FOLIO_ACCESS_TOKEN + "=xyz";
    var p = new TokenCookieParser(header);
    assertThat(p.getAccessToken(), is("xyz"));
    assertThat(p.getRefreshToken(), is("123"));
  }

  @Test
  public void testParseCookieWithoutAccessToken() {
    var header = LoginAPI.FOLIO_REFRESH_TOKEN + "=123;";
    var p = new TokenCookieParser(header);
    assertThat(p.getAccessToken(), is(nullValue()));
    assertThat(p.getRefreshToken(), is("123"));
  }

  @Test
  public void testParseCookieWithWhitespaceTabAndLF() {
    var header = "  \t  \t  " + LoginAPI.FOLIO_REFRESH_TOKEN + "=123;  " +  LoginAPI.FOLIO_ACCESS_TOKEN + "=xyz  \r\n\n";
    var p = new TokenCookieParser(header);
    assertThat(p.getAccessToken(), is("xyz"));
    assertThat(p.getRefreshToken(), is("123"));
  }

  @Test
  public void testParseCookieMissingKey() {
    var header = LoginAPI.FOLIO_ACCESS_TOKEN + "=xyz";
    assertThrows(IllegalArgumentException.class, () -> {
      new TokenCookieParser(header);
    });
  }

  @Test
  public void testParseCookieDuplicateKey() {
    var header = LoginAPI.FOLIO_ACCESS_TOKEN + "=xyz;" + LoginAPI.FOLIO_REFRESH_TOKEN + "=xyz;" + LoginAPI.FOLIO_ACCESS_TOKEN + "=xyz";
    assertThrows(IllegalArgumentException.class, () -> {
      new TokenCookieParser(header);
    });
  }
}
