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
import io.vertx.core.impl.ContextInternal;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Pavol Loffay
 */
public class FakeTracer {

  private static final Object ACTIVE_SCOPE_KEY = "active.scope";

  private final Vertx vertx;
  private AtomicInteger idGenerator = new AtomicInteger(0);
  List<Span> finishedSpans = new CopyOnWriteArrayList<>();

  public FakeTracer(Vertx vertx) {
    this.vertx = vertx;
  }

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
    return activeSpan((ContextInternal) Vertx.currentContext());
  }

  public Span activeSpan(ContextInternal ctx) {
    ConcurrentMap<Object, Object> data = ctx.localContextData();
    Scope scope = (Scope) data.get(ACTIVE_SCOPE_KEY);
    return scope != null ? scope.wrapped : null;
  }

  public Scope activate(Span span) {
    ContextInternal requestCtx = (ContextInternal) Vertx.currentContext();
    ConcurrentMap<Object, Object> data = requestCtx.localContextData();
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

  public List<Span> getFinishedSpans() {
    return Collections.unmodifiableList(finishedSpans);
  }

  public Span createSpan(int traceId, int parentId, int id) {
    return new Span(this, traceId, parentId, id);
  }
}
