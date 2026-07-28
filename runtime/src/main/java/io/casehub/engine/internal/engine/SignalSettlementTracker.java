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
package io.casehub.engine.internal.engine;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class SignalSettlementTracker {

  private final ConcurrentHashMap<UUID, SettlementState> states = new ConcurrentHashMap<>();

  public UUID registerSignal(UUID caseId) {
    UUID signalId = UUID.randomUUID();
    states.put(signalId, new SettlementState(caseId));
    return signalId;
  }

  public void incrementExpected(UUID signalId) {
    SettlementState state = states.get(signalId);
    if (state != null) {
      state.lock.lock();
      try {
        state.expected.incrementAndGet();
      } finally {
        state.lock.unlock();
      }
    }
  }

  public void markFullyDispatched(UUID signalId) {
    SettlementState state = states.get(signalId);
    if (state != null) {
      state.lock.lock();
      try {
        state.fullyDispatched.set(true);
        tryResolve(signalId, state);
      } finally {
        state.lock.unlock();
      }
    }
  }

  public void recordCompletion(UUID signalId) {
    SettlementState state = states.get(signalId);
    if (state != null) {
      state.lock.lock();
      try {
        state.completed.incrementAndGet();
        tryResolve(signalId, state);
      } finally {
        state.lock.unlock();
      }
    }
  }

  public CompletableFuture<Void> getFuture(UUID signalId) {
    SettlementState state = states.get(signalId);
    return state != null ? state.future : null;
  }

  public void remove(UUID signalId) {
    states.remove(signalId);
  }

  private void tryResolve(UUID signalId, SettlementState state) {
    if (state.fullyDispatched.get() && state.completed.get() >= state.expected.get()) {
      state.future.complete(null);
      states.remove(signalId);
    }
  }

  private static class SettlementState {
    final UUID caseId;
    final java.util.concurrent.locks.ReentrantLock lock =
        new java.util.concurrent.locks.ReentrantLock();
    final AtomicInteger expected = new AtomicInteger(0);
    final AtomicInteger completed = new AtomicInteger(0);
    final AtomicBoolean fullyDispatched = new AtomicBoolean(false);
    final CompletableFuture<Void> future = new CompletableFuture<>();

    SettlementState(UUID caseId) {
      this.caseId = caseId;
    }
  }
}
