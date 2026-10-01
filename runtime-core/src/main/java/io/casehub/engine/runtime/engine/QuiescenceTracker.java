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

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tracks case-level quiescence — the point where no workers are executing and no context change
 * events are in-flight.
 */
public class QuiescenceTracker {

  private final ConcurrentHashMap<UUID, QuiescenceState> trackers = new ConcurrentHashMap<>();

  public CompletableFuture<Void> register(UUID caseId) {
    QuiescenceState state = trackers.computeIfAbsent(caseId, k -> new QuiescenceState());
    state.lock.lock();
    try {
      if (state.future == null) {
        state.future = new CompletableFuture<>();
      }
      return state.future;
    } finally {
      state.lock.unlock();
    }
  }

  public boolean isTracking(UUID caseId) {
    return trackers.containsKey(caseId);
  }

  public void onWorkerDispatched(UUID caseId) {
    QuiescenceState state = trackers.computeIfAbsent(caseId, k -> new QuiescenceState());
    state.lock.lock();
    try {
      state.activeWorkers.incrementAndGet();
    } finally {
      state.lock.unlock();
    }
  }

  public void onWorkerCompleted(UUID caseId) {
    QuiescenceState state = trackers.get(caseId);
    if (state != null) {
      state.lock.lock();
      try {
        state.activeWorkers.decrementAndGet();
      } finally {
        state.lock.unlock();
      }
      tryResolve(caseId);
    }
  }

  public void onContextChangePublished(UUID caseId) {
    QuiescenceState state = trackers.computeIfAbsent(caseId, k -> new QuiescenceState());
    state.lock.lock();
    try {
      state.pendingContextChanges.incrementAndGet();
    } finally {
      state.lock.unlock();
    }
  }

  public void onEvaluationStarting(UUID caseId) {
    QuiescenceState state = trackers.get(caseId);
    if (state != null) {
      state.lock.lock();
      try {
        state.evaluationInProgress = true;
      } finally {
        state.lock.unlock();
      }
    }
  }

  public void onContextChangeConsumed(UUID caseId) {
    QuiescenceState state = trackers.get(caseId);
    if (state != null) {
      state.lock.lock();
      try {
        if (state.pendingContextChanges.get() > 0) {
          state.pendingContextChanges.decrementAndGet();
        }
      } finally {
        state.lock.unlock();
      }
    }
  }

  public void onEvaluationDrained(UUID caseId) {
    QuiescenceState state = trackers.get(caseId);
    if (state != null) {
      state.lock.lock();
      try {
        state.evaluationInProgress = false;
        state.drainCount.incrementAndGet();
      } finally {
        state.lock.unlock();
      }
      tryResolve(caseId);
    }
  }

  public void tryResolve(UUID caseId) {
    QuiescenceState state = trackers.get(caseId);
    if (state != null) {
      state.lock.lock();
      try {
        boolean quiescent =
            state.drainCount.get() > 0
                && state.activeWorkers.get() <= 0
                && state.pendingContextChanges.get() <= 0
                && !state.evaluationInProgress;
        if (quiescent && state.future != null) {
          state.future.complete(null);
          trackers.remove(caseId);
        } else if (quiescent && state.future == null) {
          trackers.remove(caseId);
        }
      } finally {
        state.lock.unlock();
      }
    }
  }

  public void remove(UUID caseId) {
    QuiescenceState state = trackers.remove(caseId);
    if (state != null && state.future != null && !state.future.isDone()) {
      state.future.cancel(false);
    }
  }

  private static class QuiescenceState {
    final ReentrantLock lock = new ReentrantLock();
    final AtomicInteger activeWorkers = new AtomicInteger(0);
    final AtomicInteger pendingContextChanges = new AtomicInteger(0);
    final AtomicInteger drainCount = new AtomicInteger(0);
    boolean evaluationInProgress;
    volatile CompletableFuture<Void> future;
  }
}
