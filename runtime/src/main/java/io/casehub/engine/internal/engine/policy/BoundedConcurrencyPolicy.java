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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.jboss.logging.Logger;

/**
 * Per-case semaphore with configurable permits. Limits the number of concurrent evaluations per
 * case without coalescing — all queued events evaluate individually.
 *
 * <p>With {@code permits=1}, this gives strict serialisation (like {@link
 * CoalescingSerializerPolicy} but without coalescing — every event triggers a full evaluation).
 * With {@code permits > 1}, bounded parallelism per case.
 *
 * <p>Virtual threads make the semaphore blocking effectively free (the virtual thread parks, not
 * the platform thread).
 *
 * <p>Refs casehubio/engine#771.
 */
public class BoundedConcurrencyPolicy implements CaseEvaluationPolicy {

  private static final Logger LOG = Logger.getLogger(BoundedConcurrencyPolicy.class);

  private final int maxConcurrent;
  private final ConcurrentHashMap<UUID, Semaphore> semaphores = new ConcurrentHashMap<>();

  public BoundedConcurrencyPolicy(int maxConcurrent) {
    if (maxConcurrent < 1) {
      throw new IllegalArgumentException("maxConcurrent must be >= 1, got " + maxConcurrent);
    }
    this.maxConcurrent = maxConcurrent;
  }

  @Override
  public void submit(UUID caseId, Runnable evaluator) {
    Semaphore semaphore = semaphores.computeIfAbsent(caseId, k -> new Semaphore(maxConcurrent));
    semaphore.acquireUninterruptibly();
    try {
      evaluator.run();
    } catch (Exception e) {
      LOG.errorf(e, "Evaluation failed for caseId=%s (bounded-concurrency)", caseId);
    } finally {
      semaphore.release();
    }
  }

  @Override
  public void evict(UUID caseId) {
    semaphores.remove(caseId);
  }
}
