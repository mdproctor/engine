/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.engine.flow;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class CallableDispatchRegistry {

  private final ConcurrentHashMap<String, CallableDispatcher> dispatchers =
      new ConcurrentHashMap<>();

  public void register(final String callName, final CallableDispatcher dispatcher) {
    final CallableDispatcher existing = dispatchers.putIfAbsent(callName, dispatcher);
    if (existing != null) {
      throw new IllegalStateException("Dispatcher already registered for: " + callName);
    }
  }

  public CallableDispatcher get(final String callName) {
    final CallableDispatcher d = dispatchers.get(callName);
    if (d == null) {
      throw new UnsupportedOperationException(
          "No dispatcher registered for call name: " + callName);
    }
    return d;
  }

  public boolean canHandle(final String callName) {
    return dispatchers.containsKey(callName);
  }
}
