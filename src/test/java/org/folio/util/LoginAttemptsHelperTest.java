package org.folio.util;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

class LoginAttemptsHelperTest {

  @ParameterizedTest
  @CsvSource(textBlock = """
                  1, 2, 3, 3
                  1, 2, a, 2
                  1, 2,  , 2
                  1, a, 3, 3
                  1, a,  , 1
                  9,  ,'', 9
                  """)
  void getValue(int defaultValue, String moduleValue, String jsonValue, int expected) {
    MODULE_SPECIFIC_ARGS.put("X", moduleValue);
    Future<JsonObject> future;
    if ("".equals(jsonValue)) {
      future = Future.failedFuture("fail");
    } else {
      var jsonObject = new JsonObject().put("value", jsonValue);
      future = Future.succeededFuture(jsonObject);
    }
    assertThat(LoginAttemptsHelper.getValue(future, "X", defaultValue), is(expected));
  }

}
