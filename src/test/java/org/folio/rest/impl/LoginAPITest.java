package org.folio.rest.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LoginAPITest {

  @ParameterizedTest
  @CsvSource(textBlock = """
                  , ''
                  '', ''
                  x, ''
                  :, ''
                  https://0.0.0.0, ''
                  https://0.0.0.0/, ''
                  https://localhost, ''
                  https://localhost/, ''
                  https://0.0.0.0/foo, /foo
                  https://0.0.0.0/foo/, /foo
                  https://localhost/foo/bar/baz, /foo/bar/baz
                  https://localhost/foo/bar/baz/, /foo/bar/baz
                  https://[2001:0db8:85a3:08d3::0370:7344]:8080/foo, /foo
                  """)
  void test(String okapiUrl, String expectedPath) {
    assertThat(LoginAPI.getPathFromOkapiUrl(okapiUrl), is(expectedPath));
  }

}
