/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.folio.auth.login_module_test;

import org.folio.auth.login_module.AuthResult;
import org.folio.auth.login_module.AuthUtil;
import org.folio.auth.login_module.impl.MongoAuthSource;
import de.flapdoodle.embed.mongo.MongodExecutable;
import de.flapdoodle.embed.mongo.MongodProcess;
import de.flapdoodle.embed.mongo.MongodStarter;
import de.flapdoodle.embed.mongo.config.IMongodConfig;
import de.flapdoodle.embed.mongo.config.MongodConfigBuilder;
import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.process.runtime.Network;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.RunTestOnContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.io.IOException;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import org.junit.Rule;
import org.junit.Test;
/**
 *
 * @author kurt
 */
@RunWith(VertxUnitRunner.class)
public class MongoAuthSourceTest {
  private AuthUtil authUtil = new AuthUtil();
  final static String tenant = "diku";
  
  @Rule
  public RunTestOnContext rule = new RunTestOnContext();
  
  public Future<MongoAuthSource> setUp() throws IOException {
    Vertx vertx;
    MongoClient mongoClient;
    Future<MongoAuthSource> future = Future.future();
    int mongoPort = Network.getFreeServerPort();
    MongodStarter starter = MongodStarter.getDefaultInstance();
    IMongodConfig mongodConfig = new MongodConfigBuilder()
            .version(Version.Main.PRODUCTION)
            .net(new Net(mongoPort, Network.localhostIsIPv6()))
            .build();
    MongodExecutable mongodExecutable = starter.prepare(mongodConfig);
    MongodProcess mongoD = mongodExecutable.start();
    
    JsonObject mongoConfig = new JsonObject();
    mongoConfig.put("connection_string", "mongodb://localhost:" + mongoPort);
    mongoConfig.put("db_name", "test");
    vertx = rule.vertx();
    mongoClient = MongoClient.createShared(vertx, mongoConfig);
    MongoAuthSource source = new MongoAuthSource(mongoClient, authUtil);
    
    ArrayList<JsonObject> credentialsList = new ArrayList<>();
  
    //password is "BigBadWolf"
    credentialsList.add(new JsonObject()
      .put("username", "jack")
      .put("tenant", "diku")
      .put("hash","5FB532B6D2926506E3EE32EF3AA73E6B6342E9D5")
      .put("salt","573547B9EF48329FD2285F256C0A330C27D3D0A5")
      .put("metadata", new JsonObject()));
   
    //password is "LittleNiceSheep"
    credentialsList.add(new JsonObject()
      .put("username", "jill")
      .put("tenant", "diku")
      .put("hash","C3B9C87747622CBBEBDC7C1F9F219C392E6B657B")
      .put("salt","7D10FB1E347835A05B5847FEF9033A581CC9FEDA")
      .put("metadata", new JsonObject()));
    
    //password is "OldLazyDog"
    credentialsList.add(new JsonObject()
      .put("username", "joe")
      .put("tenant", "diku")
      .put("hash","CBC698A0F5E975CABFAFEAC2B8D28896FEEE5B59")
      .put("salt","9BC945B30568C76F90795EFF062A9D6A38025D57")
      .put("metadata", new JsonObject()));
 
    ArrayList<Future> futureList = new ArrayList<>();
    
    mongoClient.createCollection("credentials", createRes -> {
      if(!createRes.succeeded()) {
        future.fail("Unable to create collection");
      } else {
        for(JsonObject credsObj : credentialsList) {
          Future<Void> insertFuture = Future.future();
          futureList.add(insertFuture);
          mongoClient.insert("credentials", credsObj, res -> {
            if(res.succeeded()) {
              insertFuture.complete();
            } else {
              insertFuture.fail("Insertion failed");
            }
          });
        }
        CompositeFuture allInsertsFuture = CompositeFuture.all(futureList);
        allInsertsFuture.setHandler(res -> {
          if(!res.succeeded()) {
            future.fail("One or more insert(s) failed");
          } else {
            future.complete(source);
          }
        });
      }
    });    
    return future;
  }
  
  @Test
  public void basicLoginTest(TestContext context) throws IOException {
    final Async async = context.async();
    setUp().setHandler(res -> {
      if(!res.succeeded()) {
        context.fail("Initialization failed");
      } else {
        MongoAuthSource source = res.result();
        JsonObject credentials = new JsonObject()
                .put("username", "jack")
                .put("password", "BigBadWolf");
        source.authenticate(credentials, tenant).setHandler(res2 -> {
          System.out.println("Got a result");
          if(res2.failed()) {
            context.fail(res2.cause());
          } else {
            AuthResult authResult = res2.result();
            context.assertTrue(authResult.getSuccess());
            async.complete();
          }
        });
      }
    });
  }
  
  
  @Test
  public void badPasswordTest(TestContext context) throws IOException {
    final Async async = context.async();
    setUp().setHandler(res -> {
      if(!res.succeeded()) {
        context.fail("Unable to initialize test");
      } else {
        JsonObject credentials = new JsonObject()
                .put("username", "jack")
                .put("password", "floWdaBgiB");
        MongoAuthSource source = res.result();
        source.authenticate(credentials, tenant).setHandler(res2 -> {
          if(res2.failed()) {
            context.fail(res2.cause());
          } else {
            AuthResult authResult = res2.result();
            context.assertFalse(authResult.getSuccess());
            async.complete();
          }
        });
      }
    });
  } 
  
  @Test
  public void badLoginTest(TestContext context) throws IOException {
    final Async async = context.async();
    setUp().setHandler(res -> {
      if(!res.succeeded()) {
        context.fail("Unable to initialize test");
      } else {
        JsonObject credentials = new JsonObject()
                .put("username", "jake")
                .put("password", "floWdaBgiB");
        MongoAuthSource source = res.result();
        source.authenticate(credentials, tenant).setHandler(res2 -> {
          if(res2.failed()) {
            context.fail(res2.cause());
          } else {
            AuthResult authResult = res2.result();
            context.assertFalse(authResult.getSuccess());
            async.complete();
          }
        });
      }
    });
  } 
  

  @Test
  public void addCredTest(TestContext context) throws IOException {
    final Async async = context.async();
    setUp().setHandler( res -> {
      if(!res.succeeded()) {
        context.fail(res.cause());
      } else {
        JsonObject newCreds = new JsonObject()
                .put("username", "frank")
                .put("password", "HotDogsLadies");
        MongoAuthSource source = res.result();
        source.addAuth(newCreds, new JsonObject(), tenant).setHandler(res2 -> {
          if(res2.failed()) {
            context.fail(res2.cause());
          } else {
            async.complete();
          }
        });
      }
    });
  }
  
/*
 
  @Test
  public void checkAuthTest(TestContext context) throws IOException {
    final Async async = context.async();
    setUp().setHandler(res -> {
      if(!res.succeeded()) {
        context.fail("Unable to initialize test");
      } else {
        JsonObject credentials = new JsonObject()
                .put("username", "jack")
                .put("password", "BigBadWolf");
        MongoAuthSource source = res.result();
        source.checkAuth(credentials).setHandler(res2 -> {
          if(res2.failed()) {
            context.fail(res2.cause());
          } else {
            context.assertTrue(res2.result());
          }
        });
      }
    });
  } 
  
*/

  
}


