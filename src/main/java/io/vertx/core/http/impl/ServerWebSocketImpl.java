/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.http.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.core.http.impl.ws.WebSocketFrameImpl;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.spi.metrics.HttpServerMetrics;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.vertx.core.http.impl.HttpUtils.*;
import static io.vertx.core.spi.metrics.Metrics.*;

/**
 * This class is optimised for performance when used on the same event loop. However it can be used safely from other threads.
 *
 * The internal state is protected using the synchronized keyword. If always used on the same event loop, then
 * we benefit from biased locking which makes the overhead of synchronized near zero.
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 *
 */
public class ServerWebSocketImpl extends WebSocketImplBase<ServerWebSocketImpl> implements ServerWebSocket {

  private static final AtomicReferenceFieldUpdater<ServerWebSocketImpl, Integer> STATUS_UPDATER =
    AtomicReferenceFieldUpdater.newUpdater(ServerWebSocketImpl.class, Integer.class, "status");
  private final Http1xServerConnection conn;
  private final long closingTimeoutMS;
  private final String scheme;
  private final String host;
  private final HostAndPort authority;
  private final String uri;
  private final String path;
  private final String query;
  private final WebSocketServerHandshaker handshaker;
  private Http1xServerRequest request;
  private volatile Integer status;
  private Promise<Integer> handshakePromise;

  ServerWebSocketImpl(ContextInternal context,
                      Http1xServerConnection conn,
                      boolean supportsContinuation,
                      long closingTimeout,
                      Http1xServerRequest request,
                      WebSocketServerHandshaker handshaker,
                      int maxWebSocketFrameSize,
                      int maxWebSocketMessageSize,
                      boolean registerWebSocketWriteHandlers) {
    super(context, conn, supportsContinuation, maxWebSocketFrameSize, maxWebSocketMessageSize, registerWebSocketWriteHandlers);
    this.conn = conn;
    this.closingTimeoutMS = closingTimeout >= 0 ? closingTimeout * 1000L : -1L;
    this.scheme = request.scheme();
    this.host = request.host();
    this.authority = request.authority();
    this.uri = request.uri();
    this.path = request.path();
    this.query = request.query();
    this.request = request;
    this.handshaker = handshaker;

    headers(request.headers());
  }

  @Override
  public String scheme() {
    return scheme;
  }

  @Override
  public String host() {
    return host;
  }

  @Override
  public HostAndPort authority() {
    return authority;
  }

  @Override
  public String uri() {
    return uri;
  }

  @Override
  public String path() {
    return path;
  }

  @Override
  public String query() {
    return query;
  }

  @Override
  public void accept() {
    if (tryHandshake(SC_SWITCHING_PROTOCOLS) != Boolean.TRUE) {
      throw new IllegalStateException("WebSocket already rejected");
    }
  }

  @Override
  public void reject() {
    reject(SC_BAD_GATEWAY);
  }

  @Override
  public void reject(int sc) {
    if (sc == SC_SWITCHING_PROTOCOLS) {
      throw new IllegalArgumentException("Invalid WebSocket rejection status code: 101");
    }
    if (tryHandshake(sc) != Boolean.TRUE) {
      throw new IllegalStateException("Cannot reject WebSocket, it has already been written to");
    }
  }

  @Override
  public Future<Void> close(short statusCode, String reason) {
    synchronized (conn) {
      if (status == null) {
        if (handshakePromise == null) {
          tryHandshake(101);
        } else {
          handshakePromise.tryComplete(101);
        }
      }
    }
    Future<Void> fut = super.close(statusCode, reason);
    fut.onComplete(v -> {
      if (closingTimeoutMS == 0L) {
        closeConnection();
      } else if (closingTimeoutMS > 0L) {
        initiateConnectionCloseTimeout(closingTimeoutMS);
      }
    });
    return fut;
  }

  @Override
  public Future<Void> writeFrame(WebSocketFrame frame) {
    synchronized (conn) {
      // if lucky, tryHandshake will return true without any need of synchronization lock on connection
      final Boolean check = tryHandshake(SC_SWITCHING_PROTOCOLS);
      if (check == null) {
        throw new IllegalStateException("Cannot write to WebSocket, it is pending accept or reject");
      }
      if (!check) {
        throw new IllegalStateException("Cannot write to WebSocket, it has been rejected");
      }
      // this is not going through super.writeFrame as we want to avoid synchronizing against the connection
      return super.unsafeWriteFrame((WebSocketFrameImpl) frame);
    }
  }

  private void handleHandshake(int sc) {
    synchronized (conn) {
      if (status == null) {
        if (sc == SC_SWITCHING_PROTOCOLS) {
          doHandshake();
        } else {
          STATUS_UPDATER.lazySet(this, sc);
          HttpUtils.sendError(conn.channel(), HttpResponseStatus.valueOf(sc));
        }
      }
    }
  }

  private void doHandshake() {
    Channel channel = conn.channel();
    Http1xServerResponse response = request.response();
    try {
      handshaker.handshake(channel, request.nettyRequest(), (HttpHeaders) response.headers(), channel.newPromise());
    } catch (Exception e) {
      response.setStatusCode(BAD_REQUEST.code()).end();
      throw e;
    } finally {
      request = null;
    }
    response.completeHandshake();
    STATUS_UPDATER.lazySet(this, SWITCHING_PROTOCOLS.code());
    subProtocol(handshaker.selectedSubprotocol());
    // remove compressor as its not needed anymore once connection was upgraded to websockets
    ChannelPipeline pipeline = channel.pipeline();
    ChannelHandler handler = pipeline.get(HttpChunkContentCompressor.class);
    if (handler != null) {
      pipeline.remove(handler);
    }
    registerHandler(conn.getContext().owner().eventBus());
  }

  Boolean tryHandshake(int sc) {
    Integer status = this.status;
    if (status != null) {
      return status == sc;
    }
    synchronized (conn) {
      assert status == null;
      status = this.status;
      if (status != null) {
        return status == sc;
      }
      if (handshakePromise == null) {
        setHandshake(Future.succeededFuture(sc));
        status = this.status;
      }
      return status == null ? null : status == sc;
    }
  }

  @Override
  public void setHandshake(Future<Integer> future, Handler<AsyncResult<Integer>> handler) {
    Future<Integer> fut = setHandshake(future);
    fut.onComplete(handler);
  }

  @Override
  public Future<Integer> setHandshake(Future<Integer> future) {
    if (future == null) {
      throw new NullPointerException();
    }
    // Change p1,p2 when we handle multiple listeners per future
    Promise<Integer> p1 = Promise.promise();
    Promise<Integer> p2 = Promise.promise();
    synchronized (conn) {
      if (handshakePromise != null) {
        throw new IllegalStateException();
      }
      handshakePromise = p1;
    }
    future.onComplete(p1);
    p1.future().onComplete(ar -> {
      if (ar.succeeded()) {
        handleHandshake(ar.result());
      } else {
        handleHandshake(500);
      }
      p2.handle(ar);
    });
    return p2.future();
  }

  @Override
  protected void handleCloseConnection() {
    closeConnection();
  }

  @Override
  protected void handleClose(boolean graceful) {
    HttpServerMetrics metrics = conn.metrics;
    if (METRICS_ENABLED && metrics != null) {
      metrics.disconnected(getMetric());
      setMetric(null);
    }
    super.handleClose(graceful);
  }
}
