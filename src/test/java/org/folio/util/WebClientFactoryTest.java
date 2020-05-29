package org.folio.util;

import org.junit.Test;
import org.junit.runner.RunWith;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.client.WebClient;

@RunWith(VertxUnitRunner.class)
public class WebClientFactoryTest {

  public class MyVerticle extends AbstractVerticle {
    @Override
    public void start(Promise<Void> startFuture) {
      WebClientFactory.init(vertx);
      WebClient client = WebClientFactory.getWebClient(vertx);
      Thread verticleThread = Thread.currentThread();
      client.getAbs("http://invalid/").send(get -> {
        WebClient client2 = WebClientFactory.getWebClient(vertx);
        if(client != client2) {
          startFuture.fail("Client mismatch: " + client.hashCode() + " != " + client2.hashCode());
        }
        Context clientContext = Vertx.currentContext();
        if (clientContext != context) {
          startFuture.fail("Context mismatch:\n" + context + " !=\n" + clientContext
              + "\n Thread mismatch:\n" + verticleThread + " != \n" + Thread.currentThread());
        } else {
          startFuture.complete();
          System.out.println(Thread.activeCount());
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