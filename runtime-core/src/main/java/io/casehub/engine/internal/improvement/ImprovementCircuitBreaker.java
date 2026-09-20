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
package io.casehub.engine.internal.improvement;

import io.casehub.api.model.stigmergy.HealthPolicy;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ImprovementCircuitBreaker implements Resettable {

  public enum CircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final ConcurrentHashMap<UUID, CircuitBreakerState> states = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Integer> halfOpenCount = new ConcurrentHashMap<>();

  public CircuitBreakerState state(UUID caseId) {
    return states.getOrDefault(caseId, CircuitBreakerState.CLOSED);
  }

  public void evaluate(UUID caseId, HealthScoreTracker tracker, HealthPolicy policy) {
    double score = tracker.computeScore(caseId, policy);
    double delta = tracker.delta(caseId, policy.effectiveHealthWindowMinutes());
    var current = state(caseId);

    switch (current) {
      case CLOSED -> {
        if (score < policy.effectiveHealthThreshold()
            || delta < -policy.effectiveHealthDeltaThreshold()) {
          states.put(caseId, CircuitBreakerState.OPEN);
        }
      }
      case OPEN -> {
        if (score >= policy.effectiveHealthThreshold()) {
          states.put(caseId, CircuitBreakerState.HALF_OPEN);
          halfOpenCount.put(caseId, 0);
        }
      }
      case HALF_OPEN -> {
        int completed = halfOpenCount.getOrDefault(caseId, 0);
        if (score < policy.effectiveHealthThreshold()) {
          states.put(caseId, CircuitBreakerState.OPEN);
        } else if (completed >= policy.effectiveHalfOpenMaxImprovements()) {
          states.put(caseId, CircuitBreakerState.CLOSED);
          halfOpenCount.remove(caseId);
        }
      }
    }
  }

  public void recordImprovementInHalfOpen(UUID caseId) {
    halfOpenCount.computeIfPresent(caseId, (k, v) -> v + 1);
  }

  public void manualReset(UUID caseId) {
    states.put(caseId, CircuitBreakerState.CLOSED);
    halfOpenCount.remove(caseId);
  }

  @Override
  public void reset() {
    states.clear();
    halfOpenCount.clear();
  }
}
