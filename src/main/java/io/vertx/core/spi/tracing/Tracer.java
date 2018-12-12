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
package io.vertx.core.spi.tracing;

import java.util.Map;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public interface Tracer<I, O> {

  default I receiveRequest(Map<Object, Object> context, Object request) {
    return null;
  }

  default void sendResponse(Map<Object, Object> context, Object response, I payload, Throwable failed) {
  }

  default O sendRequest(Map<Object, Object> context, Object request) {
    return null;
  }

  default void receiveResponse(Map<Object, Object> context, Object response, O payload, Throwable failed) {
  }

  // TracerFactory factory = ServiceHelper.loadFactory(TracerFactory.class);
}
