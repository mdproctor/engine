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

/**
 * Tracks signal settlement for {@code signalAndAwait()} operations. A signal is settled when all
 * workers triggered by the context change have completed (success or failure).
 *
 * <p>Thread-safe. Settlement resolution is atomic — the future completes exactly once when both
 * {@code fullyDispatched} and {@code completed >= expected} hold.
 *
 * <p>Refs casehubio/engine#483.
 */
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
      synchronized (state) {
        state.expected.incrementAndGet();
      }
    }
  }

  public void markFullyDispatched(UUID signalId) {
    SettlementState state = states.get(signalId);
    if (state != null) {
      synchronized (state) {
        state.fullyDispatched.set(true);
        tryResolve(signalId, state);
      }
    }
  }

  public void recordCompletion(UUID signalId) {
    SettlementState state = states.get(signalId);
    if (state != null) {
      synchronized (state) {
        state.completed.incrementAndGet();
        tryResolve(signalId, state);
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
    // Must be called while synchronized on state
    if (state.fullyDispatched.get() && state.completed.get() >= state.expected.get()) {
      state.future.complete(null);
      states.remove(signalId);
    }
  }

  private static class SettlementState {
    final UUID caseId;
    final AtomicInteger expected = new AtomicInteger(0);
    final AtomicInteger completed = new AtomicInteger(0);
    final AtomicBoolean fullyDispatched = new AtomicBoolean(false);
    final CompletableFuture<Void> future = new CompletableFuture<>();

    SettlementState(UUID caseId) {
      this.caseId = caseId;
    }
  }
}
