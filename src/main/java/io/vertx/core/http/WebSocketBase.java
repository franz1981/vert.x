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

package io.vertx.core.http;

import io.vertx.codegen.annotations.CacheReturn;
import io.vertx.codegen.annotations.Fluent;
import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.cert.X509Certificate;
import java.security.cert.Certificate;
import java.util.List;

/**
 * Base WebSocket implementation.
 * <p>
 * It implements both {@link ReadStream} and {@link WriteStream} so it can be used with
 * {@link io.vertx.core.streams.Pipe} to pipe data with flow control.
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
@VertxGen(concrete = false)
public interface WebSocketBase extends ReadStream<Buffer>, WriteStream<Buffer> {

  @Override
  WebSocketBase exceptionHandler(Handler<Throwable> handler);

  @Override
  WebSocketBase handler(Handler<Buffer> handler);

  @Override
  WebSocketBase pause();

  @Override
  WebSocketBase resume();

  @Override
  WebSocketBase fetch(long amount);

  @Override
  WebSocketBase endHandler(Handler<Void> endHandler);

  @Override
  WebSocketBase setWriteQueueMaxSize(int maxSize);

  @Override
  WebSocketBase drainHandler(Handler<Void> handler);

  /**
   * When a {@code WebSocket} is created, it may register an event handler with the event bus - the ID of that
   * handler is given by this method.
   * <p>
   * By default, no handler is registered, the feature must be enabled via {@link WebSocketConnectOptions#setRegisterWriteHandlers(boolean)} or {@link HttpServerOptions#setRegisterWebSocketWriteHandlers(boolean)}.
   * <p>
   * Given this ID, a different event loop can send a binary frame to that event handler using the event bus and
   * that buffer will be received by this instance in its own event loop and written to the underlying connection. This
   * allows you to write data to other WebSockets which are owned by different event loops.
   *
   * @return the binary handler id
   * @see WebSocketConnectOptions#setRegisterWriteHandlers(boolean)
   * @see HttpServerOptions#setRegisterWebSocketWriteHandlers(boolean)
   */
  String binaryHandlerID();

  /**
   * When a {@code WebSocket} is created, it may register an event handler with the eventbus, the ID of that
   * handler is given by {@code textHandlerID}.
   * <p>
   * By default, no handler is registered, the feature must be enabled via {@link WebSocketConnectOptions#setRegisterWriteHandlers(boolean)} or {@link HttpServerOptions#setRegisterWebSocketWriteHandlers(boolean)}.
   * <p>
   * Given this ID, a different event loop can send a text frame to that event handler using the event bus and
   * that buffer will be received by this instance in its own event loop and written to the underlying connection. This
   * allows you to write data to other WebSockets which are owned by different event loops.
   *
   * @return the text handler id
   * @see WebSocketConnectOptions#setRegisterWriteHandlers(boolean)
   * @see HttpServerOptions#setRegisterWebSocketWriteHandlers(boolean)
   */
  String textHandlerID();

  /**
   * Returns the WebSocket sub protocol selected by the WebSocket handshake.
   * <p/>
   * On the server, the value will be {@code null} when the handler receives the WebSocket callback as the
   * handshake will not be completed yet.
   */
  String subProtocol();

  /**
   * Returns the close status code received from the remote endpoint or {@code null} when not yet received.
   */
  Short closeStatusCode();

  /**
   * Returns the close reason message from the remote endpoint or {@code null} when not yet received.
   */
  String closeReason();

  /**
   *  Returns the HTTP headers.
   *
   * @return the headers
   */
  MultiMap headers();

  /**
   * Write a WebSocket frame to the connection
   *
   * @param frame  the frame to write
   * @return a future completed with the result
   */
  Future<Void> writeFrame(WebSocketFrame frame);

  /**
   * Same as {@link #writeFrame(WebSocketFrame)} but with an {@code handler} called when the operation completes
   */
  @Fluent
  @Deprecated
  WebSocketBase writeFrame(WebSocketFrame frame, Handler<AsyncResult<Void>> handler);

  /**
   * Write a final WebSocket text frame to the connection
   *
   * @param text  The text to write
   * @return a future completed with the result
   */
  Future<Void> writeFinalTextFrame(String text);

  /**
   * Same as {@link #writeFinalTextFrame(String, Handler)} but with an {@code handler} called when the operation completes
   */
  @Fluent
  @Deprecated
  WebSocketBase writeFinalTextFrame(String text, Handler<AsyncResult<Void>> handler);

  /**
   * Write a final WebSocket binary frame to the connection
   *
   * @param data  The data to write
   * @return a future completed with the result
   */
  Future<Void> writeFinalBinaryFrame(Buffer data);

  /**
   * Same as {@link #writeFinalBinaryFrame(Buffer, Handler)} but with an {@code handler} called when the operation completes
   */
  @Fluent
  @Deprecated
  WebSocketBase writeFinalBinaryFrame(Buffer data, Handler<AsyncResult<Void>> handler);

  /**
   * Writes a (potentially large) piece of binary data to the connection. This data might be written as multiple frames
   * if it exceeds the maximum WebSocket frame size.
   *
   * @param data  the data to write
   * @return a future completed with the result
   */
  Future<Void> writeBinaryMessage(Buffer data);

  /**
   * Same as {@link #writeBinaryMessage(Buffer)} but with an {@code handler} called when the operation completes
   */
  @Fluent
  @Deprecated
  WebSocketBase writeBinaryMessage(Buffer data, Handler<AsyncResult<Void>> handler);

  /**
   * Writes a (potentially large) piece of text data to the connection. This data might be written as multiple frames
   * if it exceeds the maximum WebSocket frame size.
   *
   * @param text  the data to write
   * @return a future completed with the result
   */
  Future<Void> writeTextMessage(String text);

  /**
   * Same as {@link #writeTextMessage(String)} but with an {@code handler} called when the operation completes
   */
  @Fluent
  @Deprecated
  WebSocketBase writeTextMessage(String text, Handler<AsyncResult<Void>> handler);

  /**
   * Writes a ping frame to the connection. This will be written in a single frame. Ping frames may be at most 125 bytes (octets).
   * <p>
   * This method should not be used to write application data and should only be used for implementing a keep alive or
   * to ensure the client is still responsive, see RFC 6455 Section <a href="https://tools.ietf.org/html/rfc6455#section-5.5.2">section 5.5.2</a>.
   * <p>
   * There is no handler for ping frames because RFC 6455  clearly
   * states that the only response to a ping frame is a pong frame with identical contents.
   *
   * @param data the data to write, may be at most 125 bytes
   * @param handler called when the ping frame has been successfully written
   * @return a reference to this, so the API can be used fluently
   */
  @Deprecated
  @Fluent
  WebSocketBase writePing(Buffer data, Handler<AsyncResult<Void>> handler);

  /**
   * Like {@link #writePing(Buffer, Handler)} but with an {@code handler} called when the message has been written
   * or failed to be written.
   */
  Future<Void> writePing(Buffer data);

  /**
   * Writes a pong frame to the connection. This will be written in a single frame. Pong frames may be at most 125 bytes (octets).
   * <p>
   * This method should not be used to write application data and should only be used for implementing a keep alive or
   * to ensure the client is still responsive, see RFC 6455 <a href="https://tools.ietf.org/html/rfc6455#section-5.5.2">section 5.5.2</a>.
   * <p>
   * There is no need to manually write a pong frame, as the server and client both handle responding to a ping from with a pong from
   * automatically and this is exposed to users. RFC 6455 <a href="https://tools.ietf.org/html/rfc6455#section-5.5.3">section 5.5.3</a> states that pongs may be sent unsolicited in order
   * to implement a one way heartbeat.
   *
   * @param data the data to write, may be at most 125 bytes
   * @param handler called when the pong frame has been successfully written
   * @return a reference to this, so the API can be used fluently
   */
  @Fluent
  @Deprecated
  WebSocketBase writePong(Buffer data, Handler<AsyncResult<Void>> handler);

  /**
   * Like {@link #writePong(Buffer, Handler)} but with an {@code handler} called when the message has been written
   * or failed to be written.
   */
  Future<Void> writePong(Buffer data);

  /**
   * Set a close handler. This will be called when the WebSocket is closed.
   * <p/>
   * After this callback, no more messages are expected. When the WebSocket received a close frame, the
   * {@link #closeStatusCode()} will return the status code and {@link #closeReason()} will return the reason.
   *
   * @param handler  the handler
   * @return a reference to this, so the API can be used fluently
   */
  @Fluent
  WebSocketBase closeHandler(@Nullable Handler<Void> handler);

  /**
   * Set a frame handler on the connection. This handler will be called when frames are read on the connection.
   *
   * @param handler  the handler
   * @return a reference to this, so the API can be used fluently
   */
  @Fluent
  WebSocketBase frameHandler(@Nullable Handler<WebSocketFrame> handler);

  /**
   * Set a text message handler on the connection. This handler will be called similar to the
   * {@link #binaryMessageHandler(Handler)}, but the buffer will be converted to a String first
   *
   * @param handler the handler
   * @return a reference to this, so the API can be used fluently
   */
  @Fluent
  WebSocketBase textMessageHandler(@Nullable Handler<String> handler);

  /**
   * Set a binary message handler on the connection. This handler serves a similar purpose to {@link #handler(Handler)}
   * except that if a message comes into the socket in multiple frames, the data from the frames will be aggregated
   * into a single buffer before calling the handler (using {@link WebSocketFrame#isFinal()} to find the boundaries).
   *
   * @param handler the handler
   * @return a reference to this, so the API can be used fluently
   */
  @Fluent
  WebSocketBase binaryMessageHandler(@Nullable Handler<Buffer> handler);

  /**
   * Set a pong frame handler on the connection.  This handler will be invoked every time a pong frame is received
   * on the server, and can be used by both clients and servers since the RFC 6455 <a href="https://tools.ietf.org/html/rfc6455#section-5.5.2">section 5.5.2</a> and <a href="https://tools.ietf.org/html/rfc6455#section-5.5.3">section 5.5.3</a> do not
   * specify whether the client or server sends a ping.
   * <p>
   * Pong frames may be at most 125 bytes (octets).
   * <p>
   * There is no ping handler since ping frames should immediately be responded to with a pong frame with identical content
   * <p>
   * Pong frames may be received unsolicited.
   *
   * @param handler the handler
   * @return a reference to this, so the API can be used fluently
   */
  @Fluent
  WebSocketBase pongHandler(@Nullable Handler<Buffer> handler);

  /**
   * {@inheritDoc}
   *
   * Calls {@link #close()}
   */
  @Override
  Future<Void> end();

  /**
   * {@inheritDoc}
   *
   * Calls {@link #close(Handler)}
   */
  @Override
  @Deprecated
  void end(Handler<AsyncResult<Void>> handler);

  /**
   * Close the WebSocket sending the default close frame.
   * <p/>
   * No more messages can be sent.
   *
   * @return a future completed with the result
   */
  Future<Void> close();

  /**
   * Same as {@link #close()} but with an {@code handler} called when the operation completes
   */
  @Deprecated
  void close(Handler<AsyncResult<Void>> handler);

  /**
   * Close the WebSocket sending a close frame with specified status code. You can give a look at various close payloads
   * here: RFC6455 <a href="https://tools.ietf.org/html/rfc6455#section-7.4.1">section 7.4.1</a>
   * <p/>
   * No more messages can be sent.
   *
   * @param statusCode the status code
   * @return a future completed with the result
   */
  Future<Void> close(short statusCode);

  /**
   * Same as {@link #close(short)} but with an {@code handler} called when the operation completes
   */
  @Deprecated
  void close(short statusCode, Handler<AsyncResult<Void>> handler);

  /**
   * Close sending a close frame with specified status code and reason. You can give a look at various close payloads
   * here: RFC6455 <a href="https://tools.ietf.org/html/rfc6455#section-7.4.1">section 7.4.1</a>
   * <p/>
   * No more messages can be sent.
   *
   * @param statusCode the status code
   * @param reason reason of closure
   * @return a future completed with the result
   */
  Future<Void> close(short statusCode, @Nullable String reason);

  /**
   * Same as {@link #close(short, String)} but with an {@code handler} called when the operation completes
   */
  @Deprecated
  void close(short statusCode, @Nullable String reason, Handler<AsyncResult<Void>> handler);

  /**
   * @return the remote address for this connection, possibly {@code null} (e.g a server bound on a domain socket).
   * If {@code useProxyProtocol} is set to {@code true}, the address returned will be of the actual connecting client.
   */
  @CacheReturn
  SocketAddress remoteAddress();

  /**
   * @return the local address for this connection, possibly {@code null} (e.g a server bound on a domain socket)
   * If {@code useProxyProtocol} is set to {@code true}, the address returned will be of the proxy.
   */
  @CacheReturn
  SocketAddress localAddress();

  /**
   * @return true if this {@link io.vertx.core.http.HttpConnection} is encrypted via SSL/TLS.
   */
  boolean isSsl();

  /**
   * @return {@code true} if the WebSocket is closed
   */
  boolean isClosed();

  /**
   * @return SSLSession associated with the underlying socket. Returns null if connection is
   *         not SSL.
   * @see javax.net.ssl.SSLSession
   */
  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  SSLSession sslSession();

  /**
   * @return an ordered array of the peer certificates. Returns null if connection is
   *         not SSL.
   * @throws javax.net.ssl.SSLPeerUnverifiedException SSL peer's identity has not been verified.
   * @see javax.net.ssl.SSLSession#getPeerCertificateChain()
   * @see #sslSession()
   * @deprecated instead use {@link #peerCertificates()} or {@link #sslSession()}
   */
  @Deprecated
  @GenIgnore
  X509Certificate[] peerCertificateChain() throws SSLPeerUnverifiedException;

  /**
   * @return an ordered list of the peer certificates. Returns null if connection is
   *         not SSL.
   * @throws javax.net.ssl.SSLPeerUnverifiedException SSL peer's identity has not been verified.
   * @see javax.net.ssl.SSLSession#getPeerCertificateChain()
   * @see #sslSession()
   */
  @GenIgnore()
  List<Certificate> peerCertificates() throws SSLPeerUnverifiedException;
}
