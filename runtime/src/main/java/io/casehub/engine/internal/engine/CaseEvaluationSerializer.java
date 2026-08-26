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
import jakarta.inject.Inject;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jboss.logging.Logger;

/**
 * Per-case serialiser for CONTEXT_CHANGED evaluation. One evaluation at a time per case —
 * concurrent events coalesce so only the most recent context snapshot is evaluated.
 *
 * <p>Non-blocking for submitters: when an evaluation is already running, the submitter stores the
 * evaluator and returns immediately (virtual thread cost ≈ 0). The thread running the current
 * evaluation drains pending work after completing each cycle.
 *
 * <p>This restores the per-case ordering guarantee that the Vert.x event loop provided before
 * {@code @RunOnVirtualThread} opted out of its single-threaded dispatch.
 *
 * <p>Refs casehubio/engine#771, #646.
 */
@ApplicationScoped
public class CaseEvaluationSerializer implements io.casehub.engine.common.spi.Resettable {

  private static final Logger LOG = Logger.getLogger(CaseEvaluationSerializer.class);

  @Inject QuiescenceTracker quiescenceTracker;

  private final ConcurrentHashMap<UUID, CaseGate> gates = new ConcurrentHashMap<>();

  public void submit(UUID caseId, Runnable evaluator) {
    CaseGate gate = gates.computeIfAbsent(caseId, CaseGate::new);
    gate.lock.lock();
    try {
      if (gate.evaluating) {
        gate.pendingEvaluator = evaluator;
        return;
      }
      gate.evaluating = true;
    } finally {
      gate.lock.unlock();
    }

    try {
      evaluator.run();
    } catch (Exception e) {
      LOG.errorf(e, "Evaluation failed for caseId=%s", caseId);
    } finally {
      drainPending(caseId, gate);
    }
  }

  public void evict(UUID caseId) {
    gates.remove(caseId);
  }

  @Override
  public void reset() {
    gates.clear();
  }

  private void drainPending(UUID caseId, CaseGate gate) {
    while (true) {
      Runnable next;
      gate.lock.lock();
      try {
        next = gate.pendingEvaluator;
        gate.pendingEvaluator = null;
        if (next == null) {
          gate.evaluating = false;
          if (quiescenceTracker != null) {
            quiescenceTracker.onEvaluationDrained(caseId);
          }
          return;
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

    CaseGate(UUID caseId) {
      this.caseId = caseId;
    }
  }
}
