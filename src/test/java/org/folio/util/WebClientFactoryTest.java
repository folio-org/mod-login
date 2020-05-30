package org.folio.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
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
        Context clientContext = Vertx.currentContext();
        if (clientContext != context) {
          startFuture.fail("Context mismatch:\n" + context + " !=\n" + clientContext
              + "\n Thread mismatch:\n" + verticleThread + " != \n" + Thread.currentThread());
        } else {
          startFuture.complete();
        }
      });
    }
  }

  @Test
  public void testMultipleVerticles(TestContext testContext) {
    Vertx vertx = Vertx.vertx();
    Vertx.vertx().deployVerticle(new MyVerticle(), testContext.asyncAssertSuccess(verticle1 -> {
      vertx.deployVerticle(new MyVerticle(), testContext.asyncAssertSuccess());
    }));
  }

  @Test
  public void testInit(TestContext testContext) {
    WebClientFactory.init(null);
    assertNull(WebClientFactory.getWebClient(null));

    Vertx vertx = Vertx.vertx();

    WebClientFactory.init(vertx);
    WebClient client = WebClientFactory.getWebClient(vertx);

    assertNotNull(client);

    WebClientFactory.init(vertx);
    WebClient client2 = WebClientFactory.getWebClient(vertx);

    assertEquals(client, client2);
  }

  @Test
  public void testMultipleContexts(TestContext context) {
    Async async = context.async();

    Vertx vertx = Vertx.vertx();

    WebClientFactory.init(vertx);
    WebClient client = WebClientFactory.getWebClient(vertx);
    Context ctx = vertx.getOrCreateContext();

    vertx.executeBlocking(promise -> {
      Context anotherCtx = vertx.getOrCreateContext();
      assertNotEquals(ctx, anotherCtx);
      promise.complete(WebClientFactory.getWebClient(vertx));
    }, res -> {
      assertEquals(client, res.result());
      async.complete();
    });
    async.awaitSuccess();
  }
}