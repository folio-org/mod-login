package org.folio.util;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class WebClientFactoryTest {

  public class MyVerticle extends AbstractVerticle {
    @Override
    public void start(Future<Void> startFuture) {
      Thread verticleThread = Thread.currentThread();
      WebClientFactory.getWebClient().getAbs("http://invalid/").send(get -> {
        Context clientContext = Vertx.currentContext();
        if (clientContext != context) {
          startFuture.fail("Context mismatch:\n" + context + " !=\n" + clientContext
              + "\n Thread mismatch:\n" + verticleThread + " != \n" + Thread.currentThread());
        }
      });
    }
  }

  @Test
  public void verticles(TestContext testContext) {
    Vertx vertx = Vertx.vertx();
    Vertx.vertx().deployVerticle(new MyVerticle(), testContext.asyncAssertSuccess(verticle1 -> {
      vertx.deployVerticle(new MyVerticle(), testContext.asyncAssertSuccess());
    }));
  }
}