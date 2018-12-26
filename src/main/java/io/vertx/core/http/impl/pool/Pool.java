/*
 * Copyright (c) 2011-2017 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.http.impl.pool;

import io.netty.channel.Channel;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.ConnectionPoolTooBusyException;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The pool is a queue of waiters and a list of connections.
 *
 * Pool invariants:
 * - a connection in the {@link #available} list has its {@code Holder#capacity > 0}
 * - the {@link #weight} is the sum of all inflight connections {@link Holder#weight}
 *
 * A connection is delivered to a {@link Waiter} on the connection's event loop thread, the waiter must take care of
 * calling {@link io.vertx.core.impl.ContextInternal#executeFromIO} if necessary.
 *
 * Calls to the pool are synchronized on the pool to avoid race conditions and maintain its invariants. This pool can
 * be called from different threads safely (although it is not encouraged for performance reasons, we benefit from biased
 * locking which makes the overhead of synchronized near zero), since it synchronizes on the pool.
 *
 * In order to avoid deadlocks, acquisition events (success or failure) are dispatched on the event loop thread of the
 * connection without holding the pool lock.
 *
 * To constrain the number of connections the pool maintains a {@link #weight} value that must remain below the the
 * {@link #maxWeight} value to create a connection. Weight is used instead of counting connection because this pool
 * can mix connections with different concurrency (HTTP/1 and HTTP/2) and this flexibility is necessary.
 *
 * When a connection is created an {@link #initialWeight} is added to the current weight.
 * When the channel is connected the {@link ConnectResult} callback value provides actual connection weight so it
 * can be used to correct the pool weight. When the channel fails to connect the initial weight is used
 * to correct the pool weight.
 *
 * When a connection is recycled and reaches its full capacity (i.e {@code Holder#concurrency == Holder#capacity},
 * the behavior depends on the {@link ConnectionListener#onRecycle(long)} event that releases this connection.
 * When {@code expirationTimestamp} is {@code 0L} the connection is closed, otherwise it is maintained in the pool,
 * letting the borrower define the expiration timestamp. The value is set according to the HTTP client connection
 * keep alive timeout.
 *
 * When a waiter asks for a connection, it is either added to the queue (when it's not empty) or attempted to be
 * served (from the pool or by creating a new connection) or failed. The {@link #waitersCount} is the number
 * of total waiters (the waiters in {@link #waitersQueue} but also the inflight) so we know if we can close the pool
 * or not. The {@link #waitersCount} is incremented when a waiter wants to acquire a connection succesfully (i.e
 * it is either added to the queue or served from the pool) and decremented when the it gets a reply (either with
 * a connection or with a failure).
 *
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class Pool<C> {

  /**
   * Pool state associated with a connection.
   */
  public class Holder implements ConnectionListener<C> {

    boolean removed;          // Removed
    C connection;             // The connection instance
    long concurrency;         // How many times we can borrow from the connection
    long capacity;            // How many times the connection is currently borrowed (0 <= capacity <= concurrency)
    Channel channel;          // Transport channel
    long weight;              // The weight that participates in the pool weight
    long expirationTimestamp; // The expiration timestamp when (concurrency == capacity) otherwise -1L

    @Override
    public void onConcurrencyChange(long concurrency) {
      setConcurrency(this, concurrency);
    }

    @Override
    public void onRecycle(long expirationTimestamp) {
      recycle(this, expirationTimestamp);
    }

    @Override
    public void onDiscard() {
      closed(this);
    }

    void connect(Waiter waiter) {
      connector.connect(this, context, ar -> {
        if (ar.succeeded()) {
          connectSucceeded(this, waiter, ar.result());
        } else {
          connectFailed(this, waiter, ar.cause());
        }
      });
    }

    @Override
    public String toString() {
      return "Holder[removed=" + removed + ",capacity=" + capacity + ",concurrency=" + concurrency + ",expirationTimestamp=" + expirationTimestamp + "]";
    }
  }

  private static final Logger log = LoggerFactory.getLogger(Pool.class);

  private final ContextInternal context;
  private final ConnectionProvider<C> connector;
  private final BiConsumer<Channel, C> connectionAdded;
  private final BiConsumer<Channel, C> connectionRemoved;

  private final int queueMaxSize;                                   // the queue max size (does not include inflight waiters)
  private final Queue<Waiter<C>> waitersQueue = new ArrayDeque<>(); // The waiters pending
  private final long maxWaiters;                                    // The max number of inflight waiters
  private int waitersCount;                                         // The number of waiters (including the inflight waiters not in the queue)

  private final Deque<Holder> available;                            // Available connections, i.e having capacity > 0
  private final boolean fifo;                                       // Recycling policy
  private long capacity;                                            // The total available connection capacity

  private final long initialWeight;                                 // The initial weight of a connection
  private final long maxWeight;                                     // The max weight (equivalent to max pool size)
  private long weight;                                              // The actual pool weight (equivalent to connection count)

  private boolean checkInProgress;                                  //
  private boolean closed;
  private final Handler<Void> poolClosed;


  public Pool(Context context,
              ConnectionProvider<C> connector,
              int queueMaxSize,
              long initialWeight,
              long maxWeight,
              Handler<Void> poolClosed,
              BiConsumer<Channel, C> connectionAdded,
              BiConsumer<Channel, C> connectionRemoved,
              boolean fifo) {
    this.context = (ContextInternal) context;
    this.maxWeight = maxWeight;
    this.initialWeight = initialWeight;
    this.connector = connector;
    this.queueMaxSize = queueMaxSize;
    this.maxWaiters = queueMaxSize == -1 ? -1 : (queueMaxSize + maxWeight / initialWeight);
    this.poolClosed = poolClosed;
    this.available = new ArrayDeque<>();
    this.connectionAdded = connectionAdded;
    this.connectionRemoved = connectionRemoved;
    this.fifo = fifo;
  }

  public synchronized int waitersInQueue() {
    return waitersQueue.size();
  }

  public synchronized int waitersCount() {
    return waitersCount;
  }

  public synchronized long weight() {
    return weight;
  }

  public synchronized long capacity() {
    return capacity;
  }

  /**
   * Get a connection for a waiter asynchronously.
   *
   * @param handler the handler
   * @return whether the pool can satisfy the request
   */
  public synchronized boolean getConnection(Handler<AsyncResult<C>> handler) {
    if (closed) {
      return false;
    }
    Waiter<C> waiter = new Waiter<>(handler);
    if (maxWaiters < 0  || waitersCount < maxWaiters) {
      waitersCount++;
      waitersQueue.add(waiter);
      checkProgress();
    } else {
      context.nettyEventLoop().execute(() -> {
        waiter.handler.handle(Future.failedFuture(new ConnectionPoolTooBusyException("Connection pool reached max wait queue size of " + queueMaxSize)));
      });
    }
    return true;
  }

  /**
   * Attempt to acquire a connection for the waiter, either borrowed from the pool or by creating a new connection.
   *
   * This method does not modify the waitersQueue list.
   *
   * @return wether the waiter is assigned a connection (or a future connection)
   */
  private Consumer<Waiter<C>> acquireConnection() {
    if (capacity > 0) {
      Holder conn = available.peek();
      capacity--;
      if (--conn.capacity == 0) {
        conn.expirationTimestamp = -1L;
        available.poll();
      }
      waitersCount--;
      return waiter -> waiter.handler.handle(Future.succeededFuture(conn.connection));
    } else if (weight < maxWeight) {
      weight += initialWeight;
      Holder holder  = new Holder();
      return holder::connect;
    } else {
      return null;
    }
  }

  /**
   * Close all unused connections with a {@code timestamp} greater than expiration timestamp.
   *
   * @param timestamp the timestamp value
   * @return the number of closed connections when calling this method
   */
  public synchronized int closeIdle(long timestamp) {
    int removed = 0;
    if (waitersQueue.isEmpty() && capacity > 0) {
      List<Holder> copy = new ArrayList<>(available);
      for (Holder conn : copy) {
        if (conn.capacity == conn.concurrency && conn.expirationTimestamp <= timestamp) {
          removed++;
          closeConnection(conn); // We should actually avoid that
          connector.close(conn.connection);
        }
      }
    }
    return removed;
  }

  private void checkProgress() {
    if (checkInProgress) {
      return;
    }
    checkInProgress = true;
    context.runOnContext(v -> {
      try {
        checkPending();
        synchronized (Pool.this) {
          checkClose();
        }
      } finally {
        synchronized (Pool.this) {
          checkInProgress = false;
        }
      }
    });
  }

  private void checkPending() {
    while (true) {
      Waiter<C> waiter;
      Consumer<Waiter<C>> task;
      synchronized (this) {
        if (waitersQueue.size() > 0) {
          task = acquireConnection();
          if (task == null) {
            break;
          }
          waiter = waitersQueue.poll();
        } else {
          break;
        }
      }
      task.accept(waiter);
    }
  }

  private void connectSucceeded(Holder holder, Waiter waiter, ConnectResult<C> result) {
    AsyncResult<C> res;
    synchronized (Pool.this) {
      // Update state
      initConnection(holder, result.context(), result.concurrency(), result.connection(), result.channel(), result.weight());
      // Init connection - state might change (i.e init could close the connection)
      if (holder.capacity == 0) {
        waitersQueue.add(waiter);
        res = null;
      } else {
        waitersCount--;
        if (--holder.capacity > 0) {
          available.add(holder);
          capacity += holder.capacity;
        }
        res = Future.succeededFuture(holder.connection);
      }
      checkProgress();
    }
    if (res != null) {
      waiter.handler.handle(res);
    }
  }

  private void connectFailed(Holder holder, Waiter waiter, Throwable cause) {
    AsyncResult<C> res;
    synchronized (Pool.this) {
      waitersCount--;
      weight -= initialWeight;
      holder.removed = true;
      checkProgress();
      res = Future.failedFuture(cause);
    }
    waiter.handler.handle(res);
  }

  private synchronized void setConcurrency(Holder holder, long concurrency) {
    if (concurrency < 0L) {
      throw new IllegalArgumentException("Cannot set a negative concurrency value");
    }
    if (holder.removed) {
      return;
    }
    if (holder.concurrency < concurrency) {
      long diff = concurrency - holder.concurrency;
      if (holder.capacity == 0) {
        available.add(holder);
      }
      capacity += diff;
      holder.capacity += diff;
      holder.concurrency = concurrency;
      checkProgress();
    } else if (holder.concurrency > concurrency) {
      throw new UnsupportedOperationException("Not yet implemented");
    }
  }

  private void recycle(Holder holder, long timestamp) {
    if (timestamp < 0L) {
      throw new IllegalArgumentException("Invalid TTL");
    }
    if (holder.removed) {
      return;
    }
    C toClose;
    synchronized (this) {
      if (recycleConnection(holder, timestamp)) {
        toClose = holder.connection;
      } else {
        toClose = null;
      }
    }
    if (toClose != null) {
      connector.close(holder.connection);
    } else {
      synchronized (this) {
        checkProgress();
      }
    }
  }

  private synchronized void closed(Holder holder) {
    if (holder.removed) {
      return;
    }
    closeConnection(holder);
    checkProgress();
  }

  private void closeConnection(Holder holder) {
    holder.removed = true;
    connectionRemoved.accept(holder.channel, holder.connection);
    if (holder.capacity > 0) {
      capacity -= holder.capacity;
      available.remove(holder);
      holder.capacity = 0;
    }
    weight -= holder.weight;
  }

  // These methods assume to be called under synchronization

  /**
   * Recycles a connection.
   *
   * @param holder the connection to recycle
   * @param timestamp the amount of millis the connection shall remain in the pool
   * @return {@code true} if the connection shall be closed
   */
  private boolean recycleConnection(Holder holder, long timestamp) {
    long newCapacity = holder.capacity + 1;
    if (newCapacity > holder.concurrency) {
      throw new AssertionError("Attempt to recycle a connection more than permitted");
    }
    if (timestamp == 0L && newCapacity == holder.concurrency && capacity >= waitersQueue.size()) {
      if (holder.capacity > 0) {
        capacity -= holder.capacity;
        available.remove(holder);
      }
      holder.expirationTimestamp = -1L;
      holder.capacity = 0;
      return true;
    } else {
      capacity++;
      if (holder.capacity == 0) {
        if (fifo) {
          available.addLast(holder);
        } else {
          available.addFirst(holder);
        }
      }
      holder.expirationTimestamp = timestamp;
      holder.capacity++;
      return false;
    }
  }

  private void initConnection(Holder holder, ContextInternal context, long concurrency, C conn, Channel channel, long weight) {
    this.weight += initialWeight - weight;
    holder.concurrency = concurrency;
    holder.connection = conn;
    holder.channel = channel;
    holder.weight = weight;
    holder.capacity = concurrency;
    holder.expirationTimestamp = -1L;
    connectionAdded.accept(holder.channel, holder.connection);
  }

  private void checkClose() {
    if (weight == 0 && waitersCount == 0) {
      // No waitersQueue and no connections - remove the ConnQueue
      closed = true;
      poolClosed.handle(null);
    }
  }
}
