package org.folio.rest.impl;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class LoginAPITest {

  @ParameterizedTest
  @CsvSource({
    // username=="alice"
    "http://okapi:9130, alice,      , http://okapi:9130/users?query=username%3D%3D%22alice%22",
    "http://okapi:9130, alice, abcde, http://okapi:9130/users?query=username%3D%3D%22alice%22",
    // username=="al\*ce"
    "http://okapi:9130, al*ce,      , http://okapi:9130/users?query=username%3D%3D%22al%5C%2Ace%22",
    // id=="abcde"
    "http://okapi:9130,      , abcde, http://okapi:9130/users?query=id%3D%3D%22abcde%22",
    // id=="ab\*de"
    "http://okapi:9130,      , ab*de, http://okapi:9130/users?query=id%3D%3D%22ab%5C%2Ade%22",
  })
  void buildUserLookupURL(String okapiUrl, String username, String userId, String expected) {
    assertThat(LoginAPI.buildUserLookupURL(okapiUrl, username, userId), is(expected));
  }

}
