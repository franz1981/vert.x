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

package io.vertx.test.faketracer;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.spi.tracing.Tracer;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Pavol Loffay
 */
public class FakeTracer implements Tracer<Span, Span> {

  private static final Object ACTIVE_SCOPE_KEY = "active.scope";

  private AtomicInteger idGenerator = new AtomicInteger(0);
  List<Span> finishedSpans = new CopyOnWriteArrayList<>();

  private int nextId() {
    return idGenerator.getAndIncrement();
  }

  public Span newTrace() {
    return new Span(this, nextId(), nextId(), nextId());
  }

  public Span createChild(Span span) {
    return new Span(this, span.traceId, span.id, nextId());
  }

  public Span activeSpan() {
    return activeSpan(((ContextInternal) Vertx.currentContext()).localContextData());
  }

  public Span activeSpan(Map<Object, Object> data) {
    Scope scope = (Scope) data.get(ACTIVE_SCOPE_KEY);
    return scope != null ? scope.wrapped : null;
  }

  public Scope activate(Span span) {
    return activate(((ContextInternal)Vertx.currentContext()).localContextData(), span);
  }

  public Scope activate(Map<Object, Object> data, Span span) {
    Scope toRestore = (Scope) data.get(ACTIVE_SCOPE_KEY);
    Scope active = new Scope(this, span, toRestore);
    data.put(ACTIVE_SCOPE_KEY, active);
    return active;
  }

  public void encode(Span span, MultiMap map) {
    map.set("span-trace-id", "" + span.traceId);
    map.set("span-parent-id", "" + span.parentId);
    map.set("span-id", "" + span.id);
  }

  public Span decode(MultiMap map) {
    String traceId = map.get("span-trace-id");
    String spanId = map.get("span-id");
    String spanParentId = map.get("span-parent-id");
    if (traceId != null && spanId != null && spanParentId != null) {
        return new Span(this, Integer.parseInt(traceId), Integer.parseInt(spanParentId),
          Integer.parseInt(spanId));
    }
    return null;
  }

  @Override
  public Span receiveRequest(Map<Object, Object> context, Object inbound) {
    if (inbound instanceof HttpServerRequest) {
      HttpServerRequest request = (HttpServerRequest) inbound;
      Span serverSpan;
      Span parent = decode(request.headers());
      if (parent != null) {
        serverSpan = createChild(parent);
      } else {
        serverSpan = newTrace();
      }
      serverSpan.addTag("span_kind", "server");
      serverSpan.addTag("path", request.path());
      serverSpan.addTag("query", request.query());

      // Create scope
      return activate(context, serverSpan).span();
    }
    return null;
  }

  @Override
  public void sendResponse(Map<Object, Object> context, Object response, Span span, Throwable failed) {
    if (span != null) {
      span.finish();
    }
  }

  @Override
  public Span sendRequest(Map<Object, Object> context, Object outbound) {
    if (outbound instanceof HttpClientRequest) {
      HttpClientRequest request = (HttpClientRequest) outbound;
      Span span = activeSpan(context);
      if (span == null) {
        span = newTrace();
      } else {
        span = createChild(span);
      }
      span.addTag("span_kind", "client");
      span.addTag("path", request.path());
      span.addTag("query", request.query());
      encode(span, request.headers());
      return span;
    }
    return null;
  }

  @Override
  public void receiveResponse(Map<Object, Object> context, Object response, Span span, Throwable failed) {
    if (span != null) {
      span.finish();
    }
  }

  public List<Span> getFinishedSpans() {
    return Collections.unmodifiableList(finishedSpans);
  }

  public Span createSpan(int traceId, int parentId, int id) {
    return new Span(this, traceId, parentId, id);
  }
}
