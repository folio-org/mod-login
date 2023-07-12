package org.folio.rest.impl;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.jupiter.api.Test;

class InitAPIsTest {

  @Test
  void checkResourceNotFound() {
    var future = InitAPIs.checkResource("gone");
    assertThat(future.cause().getMessage(), containsString("gone"));
  }

}
