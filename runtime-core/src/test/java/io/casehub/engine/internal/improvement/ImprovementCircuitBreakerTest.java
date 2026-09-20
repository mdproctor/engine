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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import io.casehub.api.model.stigmergy.HealthPolicy;
import io.casehub.api.spi.improvement.CapabilityArea;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImprovementCircuitBreakerTest {

  private ImprovementCircuitBreaker breaker;
  private HealthScoreTracker tracker;
  private CapabilityAreaRegistry registry;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    breaker = new ImprovementCircuitBreaker();
    registry = new CapabilityAreaRegistry();
    tracker = new HealthScoreTracker(registry);
    caseId = UUID.randomUUID();
  }

  @Test
  void defaultStateIsClosed() {
    assertThat(breaker.state(caseId))
        .isEqualTo(ImprovementCircuitBreaker.CircuitBreakerState.CLOSED);
  }

  @Test
  void tripsOpenOnLowHealth() {
    registry.register(area("stability", 0.3));
    var policy = new HealthPolicy(0.6, null, null, null, null, null);
    tracker.refresh(caseId, policy);

    breaker.evaluate(caseId, tracker, policy);

    assertThat(breaker.state(caseId)).isEqualTo(ImprovementCircuitBreaker.CircuitBreakerState.OPEN);
  }

  @Test
  void staysClosedWhenHealthy() {
    registry.register(area("stability", 0.8));
    var policy = new HealthPolicy(0.6, null, null, null, null, null);
    tracker.refresh(caseId, policy);

    breaker.evaluate(caseId, tracker, policy);

    assertThat(breaker.state(caseId))
        .isEqualTo(ImprovementCircuitBreaker.CircuitBreakerState.CLOSED);
  }

  @Test
  void manualResetReturnsToClosed() {
    registry.register(area("stability", 0.3));
    var policy = new HealthPolicy(0.6, null, null, null, null, null);
    tracker.refresh(caseId, policy);
    breaker.evaluate(caseId, tracker, policy);
    assertThat(breaker.state(caseId)).isEqualTo(ImprovementCircuitBreaker.CircuitBreakerState.OPEN);

    breaker.manualReset(caseId);

    assertThat(breaker.state(caseId))
        .isEqualTo(ImprovementCircuitBreaker.CircuitBreakerState.CLOSED);
  }

  @Test
  void halfOpenAfterRecovery() {
    registry.register(area("stability", 0.3));
    var policy = new HealthPolicy(0.6, null, null, 0, null, null);
    tracker.refresh(caseId, policy);
    breaker.evaluate(caseId, tracker, policy);
    assertThat(breaker.state(caseId)).isEqualTo(ImprovementCircuitBreaker.CircuitBreakerState.OPEN);

    registry.deprecate("stability");
    registry.register(area("stability", 0.8));
    tracker.refresh(caseId, policy);
    breaker.evaluate(caseId, tracker, policy);

    assertThat(breaker.state(caseId))
        .isEqualTo(ImprovementCircuitBreaker.CircuitBreakerState.HALF_OPEN);
  }

  @Test
  void halfOpenToClosedAfterCompletedImprovements() {
    registry.register(area("stability", 0.3));
    var policy = new HealthPolicy(0.6, null, null, 0, 2, null);
    tracker.refresh(caseId, policy);
    breaker.evaluate(caseId, tracker, policy);

    registry.deprecate("stability");
    registry.register(area("stability", 0.8));
    tracker.refresh(caseId, policy);
    breaker.evaluate(caseId, tracker, policy);
    assertThat(breaker.state(caseId))
        .isEqualTo(ImprovementCircuitBreaker.CircuitBreakerState.HALF_OPEN);

    breaker.recordImprovementInHalfOpen(caseId);
    breaker.recordImprovementInHalfOpen(caseId);
    breaker.evaluate(caseId, tracker, policy);

    assertThat(breaker.state(caseId))
        .isEqualTo(ImprovementCircuitBreaker.CircuitBreakerState.CLOSED);
  }

  @Test
  void resetClearsState() {
    registry.register(area("stability", 0.3));
    var policy = new HealthPolicy(0.6, null, null, null, null, null);
    tracker.refresh(caseId, policy);
    breaker.evaluate(caseId, tracker, policy);
    breaker.reset();
    assertThat(breaker.state(caseId))
        .isEqualTo(ImprovementCircuitBreaker.CircuitBreakerState.CLOSED);
  }

  private CapabilityArea area(String id, double health) {
    return new CapabilityArea() {
      public String id() {
        return id;
      }

      public String name() {
        return id;
      }

      public String description() {
        return "test";
      }

      public CapabilityAreaAssessment assess(UUID caseId) {
        return new CapabilityAreaAssessment(
            id,
            health,
            CapabilityAreaAssessment.LandscapePosition.AT_PARITY,
            0.5,
            0.3,
            1.67,
            Instant.now());
      }
    };
  }
}
