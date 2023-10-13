package org.folio.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.vertx.core.http.CookieSameSite;

class CookieSameSiteConfigTest {

  @AfterAll
  static void cleanup() {
    CookieSameSiteConfig.set(Map.of());
  }

  static Stream<Arguments> valid() {
    return Stream.of(
        arguments(Map.of(), CookieSameSite.LAX),
        arguments(Map.of("LOGIN_COOKIE_SAMESITE", "Strict"), CookieSameSite.STRICT),
        arguments(Map.of("LOGIN_COOKIE_SAMESITE", "Lax"), CookieSameSite.LAX),
        arguments(Map.of("LOGIN_COOKIE_SAMESITE", "None"), CookieSameSite.NONE)
        );
  }

  @ParameterizedTest
  @MethodSource
  void valid(Map<String,String> env, CookieSameSite expected) {
    CookieSameSiteConfig.set(env);
    assertThat(CookieSameSiteConfig.get(), is(expected));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "",
      "foo",
  })
  void invalid(String name) {
    var env = Map.of("LOGIN_COOKIE_SAMESITE", name);
    assertThrows(IllegalArgumentException.class, () -> CookieSameSiteConfig.set(env));
  }
}
