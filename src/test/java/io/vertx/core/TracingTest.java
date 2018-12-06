/*
 * Copyright (c) 2011-2018 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.core;

import io.vertx.core.http.*;
import io.vertx.core.http.impl.HttpClientRequestImpl;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.core.metrics.impl.DummyVertxMetrics;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.spi.metrics.HttpClientMetrics;
import io.vertx.core.spi.metrics.HttpServerMetrics;
import io.vertx.test.core.VertxTestBase;
import io.vertx.test.faketracer.Scope;
import io.vertx.test.faketracer.Span;
import io.vertx.test.faketracer.FakeTracer;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TracingTest extends VertxTestBase {

  private FakeTracer tracer;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    tracer = new FakeTracer(vertx);
  }

  @Override
  protected VertxOptions getOptions() {
    return super.getOptions()
      .setMetricsOptions(new MetricsOptions().setFactory(options -> new DummyVertxMetrics() {
        @Override
        public HttpServerMetrics<Scope, Void, Void> createHttpServerMetrics(HttpServerOptions options, SocketAddress localAddress) {
          return new HttpServerMetrics<Scope, Void, Void>() {
            @Override
            public Scope requestBegin(Void socketMetric, HttpServerRequest request) {

              Span serverSpan;
              Span parent = tracer.decode(request.headers());
              if (parent != null) {
                serverSpan = tracer.createChild(parent);
              } else {
                serverSpan = tracer.newTrace();
              }
              serverSpan.addTag("span_kind", "server");
              serverSpan.addTag("path", request.path());
              serverSpan.addTag("query", request.query());

              // Create scope
              return tracer.activate(serverSpan);
            }
            @Override
            public void afterRequestBegin(Scope scope) {
            }
            @Override
            public void responseBegin(Scope scope, HttpServerResponse response) {
              scope.span().finish();
            }
          };
        }
        @Override
        public HttpClientMetrics<Span, Void, Void, Void, Void> createHttpClientMetrics(HttpClientOptions options) {
          return new HttpClientMetrics<Span, Void, Void, Void, Void>() {
            @Override
            public Span requestBegin(Void endpointMetric, Void socketMetric, SocketAddress localAddress, SocketAddress remoteAddress, HttpClientRequest request) {

              HttpClientRequestImpl impl = (HttpClientRequestImpl) request;
              ContextInternal ctx = impl.stream.getContext();

              Span span = tracer.activeSpan(ctx);
              if (span == null) {
                span = tracer.newTrace();
              } else {
                span = tracer.createChild(span);
              }
              span.addTag("span_kind", "client");
              span.addTag("path", request.path());
              span.addTag("query", request.query());
              tracer.encode(span, request.headers());
              return span;
            }
            @Override
            public void responseBegin(Span span, HttpClientResponse response) {
              span.finish();
            }
          };
        }
      }).setEnabled(true));
  }

  @Test
  public void testHttpServerRequest() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    HttpClient client = vertx.createHttpClient();
    vertx.createHttpServer().requestHandler(req -> {
      assertNotNull(tracer.activeSpan());
      req.response().end();
    }).listen(8080, "localhost", onSuccess(v -> {
      latch.countDown();
    }));
    awaitLatch(latch);
    Context ctx = vertx.getOrCreateContext();
    ctx.runOnContext(v -> {
      Span rootSpan = tracer.newTrace();
      tracer.activate(rootSpan);
      client.getNow(8080, "localhost", "/1", onSuccess(resp -> {
        assertEquals(rootSpan, tracer.activeSpan());
        assertEquals(200, resp.statusCode());
        List<Span> finishedSpans = tracer.getFinishedSpans();
        System.out.println("finishedSpans = " + finishedSpans);
      /*
      assertEquals(4, finishedSpans.size());
      assertSingleTrace(finishedSpans);
      */
        testComplete();
      }));
    });
    await();
    // assertEquals(rootSpan, tracer.activeSpan());
  }

  @Test
  public void testHttpServerRequestWithClient() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    HttpClient client = vertx.createHttpClient();
    vertx.createHttpServer().requestHandler(req -> {
      assertNotNull(tracer.activeSpan());
      switch (req.path()) {
        case "/1": {
          vertx.setTimer(10, id -> {
            client.getNow(8080, "localhost", "/2", resp -> {
              req.response().end();
            });
          });
          break;
        }
        case "/2": {
          req.response().end();
          break;
        }
        default: {
          req.response().setStatusCode(500).end();
        }
      }
    }).listen(8080, "localhost", onSuccess(v -> {
      latch.countDown();
    }));
    awaitLatch(latch);
    Context ctx = vertx.getOrCreateContext();
    ctx.runOnContext(v -> {
      Span rootSpan = tracer.newTrace();
      tracer.activate(rootSpan);
      client.getNow(8080, "localhost", "/1", onSuccess(resp -> {
        assertEquals(rootSpan, tracer.activeSpan());
        assertEquals(200, resp.statusCode());
        List<Span> finishedSpans = tracer.getFinishedSpans();
        // client request to /1, server request /1, client request /2, server request /2
        assertEquals(4, finishedSpans.size());
        assertSingleTrace(finishedSpans);
        assertEquals(rootSpan, tracer.activeSpan());
        testComplete();
      }));
    });
    await();
  }

  @Test
  public void testMultipleHttpServerRequest() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    HttpClient client = vertx.createHttpClient();
    vertx.createHttpServer().requestHandler(req -> {
      assertNotNull(tracer.activeSpan());
      switch (req.path()) {
        case "/1": {
          vertx.setTimer(10, id -> {
            client.getNow(8080, "localhost", "/2?" + req.query(), resp -> {
              req.response().end();
            });
          });
          break;
        }
        case "/2": {
          req.response().end();
          break;
        }
        default: {
          req.response().setStatusCode(500).end();
        }
      }
    }).listen(8080, "localhost", onSuccess(v -> latch.countDown()));
    awaitLatch(latch);

    int numberOfRequests = 4;
    Context ctx = vertx.getOrCreateContext();
    ctx.runOnContext(v -> {
      Span rootSpan = tracer.newTrace();
      tracer.activate(rootSpan);
      for (int i = 0; i < numberOfRequests; i++) {
        client.getNow(8080, "localhost", "/1?id=" + i, onSuccess(resp -> {
          assertEquals(rootSpan, tracer.activeSpan());
          assertEquals(200, resp.statusCode());
        }));
      }
    });


    waitUntil(() -> tracer.getFinishedSpans().size() == 4 * numberOfRequests);
    List<Span> finishedSpans = tracer.getFinishedSpans();
    assertEquals(4 * numberOfRequests, finishedSpans.size());
    assertSingleTrace(finishedSpans);

    Map<Integer, Span> spanMap = finishedSpans.stream()
      .collect(Collectors.toMap(o -> o.id, Function.identity()));

    List<Span> lastServerSpans = finishedSpans.stream()
      .filter(mockSpan ->  mockSpan.getTags().get("span_kind").equals("server"))
      .filter(mockSpan -> mockSpan.getTags().get("path").contains("/2"))
      .collect(Collectors.toList());
    assertEquals(numberOfRequests, lastServerSpans.size());

    for (Span server2Span: lastServerSpans) {
      String queryStr = server2Span.getTags().get("query");
      Span client2Span = spanMap.get(server2Span.parentId);
      assertEquals("/2", client2Span.getTags().get("path"));
      assertEquals("client", client2Span.getTags().get("span_kind"));
      assertEquals(queryStr, client2Span.getTags().get("query"));
      Span server1Span = spanMap.get(client2Span.parentId);
      assertEquals("/1", server1Span.getTags().get("path"));
      assertEquals("server", server1Span.getTags().get("span_kind"));
      assertEquals(queryStr, server1Span.getTags().get("query"));
      Span client1Span = spanMap.get(server1Span.parentId);
      assertEquals("/1", client1Span.getTags().get("path"));
      assertEquals("client", client1Span.getTags().get("span_kind"));
      assertEquals(queryStr, client1Span.getTags().get("query"));
    }
  }

  private void assertSingleTrace(List<Span> spans) {
    for (int i = 1; i < spans.size(); i++) {
      assertEquals(spans.get(i - 1).traceId, spans.get(i).traceId);
    }
  }
}
