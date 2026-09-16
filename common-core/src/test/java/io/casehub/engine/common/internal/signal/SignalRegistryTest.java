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
package io.casehub.engine.common.internal.signal;

import static org.assertj.core.api.Assertions.*;

import io.casehub.api.model.signal.PerceivedSignal;
import io.casehub.api.model.signal.Signal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignalRegistryTest {

  private SignalRegistry registry;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    registry = new SignalRegistry();
    caseId = UUID.randomUUID();
  }

  @Test
  void deposit_newSignal_returnsTrue() {
    boolean result = registry.deposit(caseId, "trail", 0.8, Duration.ofMinutes(5), "agent-a", 100);
    assertThat(result).isTrue();
    assertThat(registry.signalCount(caseId)).isEqualTo(1);
  }

  @Test
  void deposit_reinforcement_updatesStrengthAndCount() {
    Instant now = Instant.now();
    registry.deposit(caseId, "trail", 0.5, Duration.ofMinutes(5), "agent-a", 100, now);
    registry.deposit(
        caseId, "trail", 0.8, Duration.ofMinutes(5), "agent-b", 100, now.plusMillis(100));

    Map<String, PerceivedSignal> perceived = registry.perceive(caseId, 0.01, now.plusMillis(100));
    assertThat(perceived).containsKey("trail");
    PerceivedSignal signal = perceived.get("trail");
    assertThat(signal.reinforcementCount()).isEqualTo(2);
    assertThat(signal.lastSource()).isEqualTo("agent-b");
    assertThat(signal.effectiveStrength()).isGreaterThanOrEqualTo(0.8);
  }

  @Test
  void deposit_exceedsMax_returnsFalse() {
    registry.deposit(caseId, "s1", 0.5, Duration.ofMinutes(5), "a", 2);
    registry.deposit(caseId, "s2", 0.5, Duration.ofMinutes(5), "a", 2);
    boolean result = registry.deposit(caseId, "s3", 0.5, Duration.ofMinutes(5), "a", 2);
    assertThat(result).isFalse();
    assertThat(registry.signalCount(caseId)).isEqualTo(2);
  }

  @Test
  void deposit_reinforcementDoesNotCountAgainstMax() {
    registry.deposit(caseId, "s1", 0.5, Duration.ofMinutes(5), "a", 2);
    registry.deposit(caseId, "s2", 0.5, Duration.ofMinutes(5), "a", 2);
    boolean result = registry.deposit(caseId, "s1", 0.9, Duration.ofMinutes(5), "b", 2);
    assertThat(result).isTrue();
  }

  @Test
  void perceive_filtersExpiredSignals() {
    registry.deposit(caseId, "trail", 0.5, Duration.ofMinutes(5), "a", 100);
    registry.markExpired(caseId, "trail");
    Map<String, PerceivedSignal> perceived = registry.perceive(caseId, 0.01);
    assertThat(perceived).isEmpty();
  }

  @Test
  void perceive_emptyCase_returnsEmpty() {
    Map<String, PerceivedSignal> perceived = registry.perceive(UUID.randomUUID(), 0.01);
    assertThat(perceived).isEmpty();
  }

  @Test
  void findNewlyExpired_detectsCrossedThreshold() {
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    registry.deposit(caseId, "fast-decay", 0.02, Duration.ofMillis(1), "a", 100, start);

    Instant later = start.plusMillis(100);
    List<Signal> expired = registry.findNewlyExpired(caseId, 0.01, later);
    assertThat(expired).hasSize(1);
    assertThat(expired.get(0).name()).isEqualTo("fast-decay");
  }

  @Test
  void findNewlyExpired_alreadyMarked_notReturned() {
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    registry.deposit(caseId, "fast-decay", 0.02, Duration.ofMillis(1), "a", 100, start);

    registry.markExpired(caseId, "fast-decay");
    Instant later = start.plusMillis(100);
    List<Signal> expired = registry.findNewlyExpired(caseId, 0.01, later);
    assertThat(expired).isEmpty();
  }

  @Test
  void evictByCase_removesAllSignals() {
    registry.deposit(caseId, "s1", 0.5, Duration.ofMinutes(5), "a", 100);
    registry.deposit(caseId, "s2", 0.5, Duration.ofMinutes(5), "a", 100);
    registry.evictByCase(caseId);
    assertThat(registry.signalCount(caseId)).isZero();
    assertThat(registry.perceive(caseId, 0.01)).isEmpty();
  }

  @Test
  void reset_clearsAllCases() {
    UUID case1 = UUID.randomUUID();
    UUID case2 = UUID.randomUUID();
    registry.deposit(case1, "s1", 0.5, Duration.ofMinutes(5), "a", 100);
    registry.deposit(case2, "s2", 0.5, Duration.ofMinutes(5), "a", 100);
    registry.reset();
    assertThat(registry.signalCount(case1)).isZero();
    assertThat(registry.signalCount(case2)).isZero();
  }

  @Test
  void deposit_expiredSignal_treatedAsNew() {
    Instant now = Instant.now();
    registry.deposit(caseId, "trail", 0.5, Duration.ofMinutes(5), "agent-a", 100, now);
    registry.markExpired(caseId, "trail");
    boolean result =
        registry.deposit(
            caseId, "trail", 0.9, Duration.ofMinutes(5), "agent-b", 100, now.plusMillis(100));
    assertThat(result).isTrue();
    Map<String, PerceivedSignal> perceived = registry.perceive(caseId, 0.01, now.plusMillis(100));
    assertThat(perceived.get("trail").reinforcementCount()).isEqualTo(1);
    assertThat(perceived.get("trail").lastSource()).isEqualTo("agent-b");
  }

  @Test
  void perceive_decaysStrengthOverTime() {
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    registry.deposit(caseId, "trail", 1.0, Duration.ofMinutes(5), "a", 100, start);

    Instant oneHalfLife = start.plus(Duration.ofMinutes(5));
    Map<String, PerceivedSignal> perceived = registry.perceive(caseId, 0.01, oneHalfLife);
    assertThat(perceived.get("trail").effectiveStrength()).isCloseTo(0.5, within(0.01));
  }

  @Test
  void perceive_filtersBelowThreshold() {
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    registry.deposit(caseId, "trail", 0.02, Duration.ofMinutes(5), "a", 100, start);

    Instant later = start.plus(Duration.ofMinutes(6));
    Map<String, PerceivedSignal> perceived = registry.perceive(caseId, 0.01, later);
    assertThat(perceived).isEmpty();
  }
}
