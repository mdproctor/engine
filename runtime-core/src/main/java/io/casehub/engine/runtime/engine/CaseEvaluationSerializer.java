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
package io.casehub.engine.runtime.engine;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.jboss.logging.Logger;

public class CaseEvaluationSerializer implements io.casehub.engine.common.spi.Resettable {

  private static final Logger LOG = Logger.getLogger(CaseEvaluationSerializer.class);
  private static final long DRAIN_TIMEOUT_SECONDS = 5;

  private final QuiescenceTracker quiescenceTracker;
  private final ConcurrentHashMap<UUID, CaseGate> gates = new ConcurrentHashMap<>();
  private volatile boolean closed;
  private final AtomicInteger activeCount = new AtomicInteger();
  private volatile CountDownLatch drainLatch;

  public CaseEvaluationSerializer(QuiescenceTracker quiescenceTracker) {
    this.quiescenceTracker = quiescenceTracker;
  }

  public Set<UUID> submit(UUID caseId, Runnable evaluator, UUID signalId) {
    if (closed) {
      return Set.of();
    }

    CaseGate gate = gates.computeIfAbsent(caseId, CaseGate::new);
    gate.lock.lock();
    try {
      if (gate.evaluating) {
        gate.pendingEvaluator = evaluator;
        if (signalId != null) {
          gate.pendingSignalIds.add(signalId);
        }
        return Set.of();
      }
      gate.evaluating = true;
      activeCount.incrementAndGet();
    } finally {
      gate.lock.unlock();
    }

    Set<UUID> allSignalIds = signalId != null ? new HashSet<>(Set.of(signalId)) : new HashSet<>();

    try {
      evaluator.run();
    } catch (Exception e) {
      LOG.errorf(e, "Evaluation failed for caseId=%s", caseId);
    } finally {
      Set<UUID> drainedSignals = drainPending(caseId, gate);
      allSignalIds.addAll(drainedSignals);
    }

    return allSignalIds;
  }

  public void evict(UUID caseId) {
    gates.remove(caseId);
  }

  @Override
  public void reset() {
    closed = true;
    if (activeCount.get() > 0) {
      drainLatch = new CountDownLatch(1);
      if (activeCount.get() > 0) {
        try {
          drainLatch.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          LOG.warn("Reset interrupted while draining evaluations");
        }
      }
    }
    gates.clear();
    drainLatch = null;
    closed = false;
  }

  private Set<UUID> drainPending(UUID caseId, CaseGate gate) {
    Set<UUID> accumulated = new HashSet<>();
    while (true) {
      Runnable next;
      gate.lock.lock();
      try {
        next = gate.pendingEvaluator;
        gate.pendingEvaluator = null;
        accumulated.addAll(gate.pendingSignalIds);
        gate.pendingSignalIds.clear();
        if (next == null) {
          gate.evaluating = false;
          activeCount.decrementAndGet();
          CountDownLatch latch = drainLatch;
          if (latch != null && activeCount.get() == 0) {
            latch.countDown();
          }
          if (quiescenceTracker != null) {
            quiescenceTracker.onEvaluationDrained(caseId);
          }
          return accumulated;
        }
      } finally {
        gate.lock.unlock();
      }
      try {
        next.run();
      } catch (Exception e) {
        LOG.errorf(e, "Coalesced evaluation failed for caseId=%s", caseId);
      }
    }
  }

  private static final class CaseGate {
    final UUID caseId;
    final ReentrantLock lock = new ReentrantLock();
    boolean evaluating;
    Runnable pendingEvaluator;
    final Set<UUID> pendingSignalIds = new HashSet<>();

    CaseGate(UUID caseId) {
      this.caseId = caseId;
    }
  }
}
