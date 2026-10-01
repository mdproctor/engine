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
package io.casehub.engine.internal.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.casehub.api.spi.observation.InterestDeclaration;
import io.casehub.api.spi.observation.ObservationConfig;
import io.casehub.engine.common.internal.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultInterestSpaceTest {

  private ObservationRegistry registry;
  private DefaultInterestSpace interestSpace;
  private final UUID caseId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    registry = new ObservationRegistry();
    interestSpace =
        new DefaultInterestSpace(registry, caseId, "agent-1", null, ObservationConfig.defaults());
  }

  @Test
  void registerKeyThresholdCreatesObserver() {
    var reg =
        interestSpace.register(
            new InterestDeclaration.KeyThreshold(
                "riskScore", InterestDeclaration.ComparisonOperator.GTE, 0.7));
    assertNotNull(reg);
    assertNotNull(reg.interestId());
    assertNotNull(reg.registeredAt());
    assertEquals(1, registry.observerCount(caseId));
  }

  @Test
  void registerSignalThresholdCreatesObserver() {
    var reg =
        interestSpace.register(
            new InterestDeclaration.SignalThreshold(
                "danger", InterestDeclaration.ComparisonOperator.GT, 0.5));
    assertNotNull(reg);
    assertEquals(1, registry.observerCount(caseId));
  }

  @Test
  void registerKeyCorrelationCreatesObserver() {
    var reg =
        interestSpace.register(new InterestDeclaration.KeyCorrelation(Set.of("a", "b"), ".a > .b"));
    assertNotNull(reg);
    assertEquals(1, registry.observerCount(caseId));
  }

  @Test
  void registerTemporalSequenceCreatesObserver() {
    var reg =
        interestSpace.register(
            new InterestDeclaration.TemporalSequence(
                List.of(
                    new InterestDeclaration.SequenceStep("login", null),
                    new InterestDeclaration.SequenceStep("transfer", null)),
                Duration.ofMinutes(5)));
    assertNotNull(reg);
    assertEquals(1, registry.observerCount(caseId));
  }

  @Test
  void registerJqInterestCreatesObserver() {
    var reg = interestSpace.register(new InterestDeclaration.JqInterest(".x > 1", Set.of("x")));
    assertNotNull(reg);
    assertEquals(1, registry.observerCount(caseId));
  }

  @Test
  void jqInterestRejectedWhenDisabled() {
    var config = new ObservationConfig(50, Duration.ofMinutes(5), 20, false);
    var restricted = new DefaultInterestSpace(registry, caseId, "agent-1", null, config);
    assertThrows(
        IllegalArgumentException.class,
        () -> restricted.register(new InterestDeclaration.JqInterest(".x > 1", Set.of("x"))));
  }

  @Test
  void deregisterRemovesObserver() {
    var reg =
        interestSpace.register(
            new InterestDeclaration.KeyThreshold(
                "x", InterestDeclaration.ComparisonOperator.GT, 1.0));
    assertEquals(1, registry.observerCount(caseId));
    interestSpace.deregister(reg.interestId());
    assertEquals(0, registry.observerCount(caseId));
  }

  @Test
  void mineReturnsOwnRegistrations() {
    interestSpace.register(
        new InterestDeclaration.KeyThreshold("a", InterestDeclaration.ComparisonOperator.GT, 1.0));
    interestSpace.register(
        new InterestDeclaration.KeyThreshold("b", InterestDeclaration.ComparisonOperator.LT, 0.5));
    var mine = interestSpace.mine();
    assertEquals(2, mine.size());
  }

  @Test
  void landscapeAggregatesCorrectly() {
    interestSpace.register(
        new InterestDeclaration.KeyThreshold(
            "riskScore", InterestDeclaration.ComparisonOperator.GT, 0.7));
    interestSpace.register(
        new InterestDeclaration.SignalThreshold(
            "danger", InterestDeclaration.ComparisonOperator.GTE, 0.5));
    var landscape = interestSpace.landscape();
    assertEquals(1, landscape.keyObserverCounts().getOrDefault("riskScore", 0));
    assertEquals(1, landscape.signalObserverCounts().getOrDefault("danger", 0));
    assertEquals(2, landscape.totalObserverCount());
  }
}
