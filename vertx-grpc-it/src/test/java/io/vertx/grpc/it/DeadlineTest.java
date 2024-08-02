/*
 * Copyright (c) 2011-2022 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.grpc.it;

//import io.grpc.Context;
//import io.grpc.Status;
//import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.examples.helloworld.VertxGreeterGrpcClient;
import io.grpc.examples.helloworld.VertxGreeterGrpcServer;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.StreamResetException;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.grpc.client.GrpcClient;
import io.vertx.grpc.client.GrpcClientOptions;
import io.vertx.grpc.server.GrpcServer;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.server.GrpcServerOptions;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class DeadlineTest extends ProxyTestBase {

  @Test
  public void testAutomaticPropagation(TestContext should) {
    Async latch = should.async(3);
    GrpcClientOptions clientOptions = new GrpcClientOptions().setScheduleDeadlineAutomatically(true);
    GrpcServerOptions serverOptions = new GrpcServerOptions().setDeadlinePropagation(true);
    GrpcClient client = GrpcClient.client(vertx, clientOptions);
    Future<HttpServer> server = vertx.createHttpServer().requestHandler(GrpcServer.server(vertx, serverOptions)
      .callHandler(VertxGreeterGrpcServer.SayHello, call -> {
      should.assertTrue(call.timeout() > 0L);
      call.response().exceptionHandler(err -> {
        if (err instanceof StreamResetException) {
          should.assertTrue(err instanceof StreamResetException);
          StreamResetException sre = (StreamResetException) err;
          should.assertEquals(8L, sre.getCode());
          latch.countDown();
        }
      });
    })).listen(8080, "localhost");
    VertxGreeterGrpcClient stub = new VertxGreeterGrpcClient(client, SocketAddress.inetSocketAddress(8080, "localhost"));
    VertxGreeterGrpcServer.GreeterApi impl = new VertxGreeterGrpcServer.GreeterApi() {
      @Override
      public Future<HelloReply> sayHello(HelloRequest request) {
        // todo : need to find a way to make this available ????
//        should.assertNotNull(Context.current().getDeadline());
        return stub
          .sayHello(HelloRequest.newBuilder().setName("Julien").build())
          .andThen(ar -> {
            if (ar.failed()) {
              Throwable err = ar.cause();
              should.assertTrue(err instanceof StreamResetException);
              StreamResetException sre = (StreamResetException) err;
              should.assertEquals(8L, sre.getCode());
              latch.countDown();
            } else {
              should.fail();
            }
          });
      }
    };
    GrpcServer proxy = GrpcServer.server(vertx, serverOptions);
    impl.bind_sayHello(proxy);
    HttpServer proxyServer = vertx.createHttpServer().requestHandler(proxy);
    server.flatMap(v -> proxyServer.listen(8081, "localhost")).onComplete(should.asyncAssertSuccess(v -> {
      client.request(SocketAddress.inetSocketAddress(8081, "localhost"), VertxGreeterGrpcClient.SayHello)
        .onComplete(should.asyncAssertSuccess(callRequest -> {
          callRequest.response().onComplete(should.asyncAssertFailure(err -> {
            // TODO : this should be mapped to a grpc response status properly instead of being an error
            should.assertTrue(err instanceof StreamResetException);
            StreamResetException sre = (StreamResetException) err;
            should.assertEquals(8L, sre.getCode());
            latch.countDown();
          }));
          callRequest.timeout(2, TimeUnit.SECONDS).end(HelloRequest.newBuilder().setName("Julien").build());
        }));
    }));
    latch.awaitSuccess();
  }
}
