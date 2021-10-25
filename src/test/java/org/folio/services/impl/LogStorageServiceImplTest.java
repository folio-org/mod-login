package org.folio.services.impl;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.folio.cql2pgjson.exception.FieldException;
import org.junit.jupiter.api.Test;

class LogStorageServiceImplTest {

  @Test
  void getCqlWrapper() throws FieldException {
    String query = LogStorageServiceImpl.getCqlWrapper("ab*de").getQuery();
    assertThat(query, is("userId==\"ab\\*de\""));
  }

}
