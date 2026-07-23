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
package io.casehub.engine.internal.engine.policy;

import io.casehub.api.engine.CaseEvaluationPolicy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jboss.logging.Logger;

/**
 * Decorator that gates the next evaluation until all workers dispatched by the previous evaluation
 * have settled. Wraps an inner policy (typically {@link CoalescingSerializerPolicy}) that handles
 * serialisation and coalescing.
 *
 * <p>After the inner policy's evaluator completes, this policy blocks the evaluating thread until
 * {@link #notifySettlement(UUID)} is called for the case. On a virtual thread the block is
 * effectively free. If no workers were dispatched (settlement is immediate), the gate opens
 * instantly.
 *
 * <p>Strictest cascade control: the slowest worker gates the entire evaluation cycle. Use when
 * cases must fully settle between evaluation rounds.
 *
 * <p>Refs casehubio/engine#771.
 */
public class SettlementGatedPolicy implements CaseEvaluationPolicy {

  private static final Logger LOG = Logger.getLogger(SettlementGatedPolicy.class);

  private final CaseEvaluationPolicy inner;
  private final ConcurrentHashMap<UUID, SettlementGate> gates = new ConcurrentHashMap<>();

  public SettlementGatedPolicy(CaseEvaluationPolicy inner) {
    this.inner = inner;
  }

  @Override
  public void submit(UUID caseId, Runnable evaluator) {
    inner.submit(caseId, () -> {
      SettlementGate gate = gates.computeIfAbsent(caseId, SettlementGate::new);
      gate.lock.lock();
      try {
        gate.future = new CompletableFuture<>();
        gate.settled = false;
      } finally {
        gate.lock.unlock();
      }

      try {
        evaluator.run();
      } catch (Exception e) {
        LOG.errorf(e, "Evaluation failed for caseId=%s (settlement-gated)", caseId);
        notifySettlement(caseId);
        return;
      }

      gate.lock.lock();
      try {
        if (gate.settled) {
          return;
        }
      } finally {
        gate.lock.unlock();
      }

      try {
        gate.future.join();
      } catch (Exception e) {
        LOG.warnf(e, "Settlement wait interrupted for caseId=%s", caseId);
      }
    });
  }

  @Override
  public void notifySettlement(UUID caseId) {
    SettlementGate gate = gates.get(caseId);
    if (gate == null) {
      return;
    }
    gate.lock.lock();
    try {
      gate.settled = true;
      if (gate.future != null) {
        gate.future.complete(null);
      }
    } finally {
      gate.lock.unlock();
    }
  }

  @Override
  public void evict(UUID caseId) {
    gates.remove(caseId);
    inner.evict(caseId);
  }

  private static final class SettlementGate {
    final UUID caseId;
    final ReentrantLock lock = new ReentrantLock();
    CompletableFuture<Void> future;
    boolean settled;

    SettlementGate(UUID caseId) {
      this.caseId = caseId;
    }
  }
}
