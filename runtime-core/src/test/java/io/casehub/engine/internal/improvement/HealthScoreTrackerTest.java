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
import static org.assertj.core.api.Assertions.within;

import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import io.casehub.api.model.stigmergy.HealthPolicy;
import io.casehub.api.spi.improvement.CapabilityArea;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HealthScoreTrackerTest {

  private HealthScoreTracker tracker;
  private CapabilityAreaRegistry registry;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    registry = new CapabilityAreaRegistry();
    tracker = new HealthScoreTracker(registry);
    caseId = UUID.randomUUID();
  }

  @Test
  void scoreNormalisesCustomWeights() {
    registry.register(area("stability", 0.8));
    registry.register(area("performance", 0.4));
    var policy =
        new HealthPolicy(
            null, null, null, null, null, Map.of("stability", 3.0, "performance", 1.0));
    double score = tracker.computeScore(caseId, policy);
    double expected = (3.0 * 0.8 + 1.0 * 0.4) / (3.0 + 1.0);
    assertThat(score).isCloseTo(expected, within(0.001));
  }

  @Test
  void snapshotIsNullBeforeRefresh() {
    assertThat(tracker.latestSnapshot(caseId)).isNull();
  }

  @Test
  void refreshCreatesSnapshot() {
    registry.register(area("stability", 0.8));
    var policy = new HealthPolicy(null, null, null, null, null, null);
    tracker.refresh(caseId, policy);
    assertThat(tracker.latestSnapshot(caseId)).isNotNull();
    assertThat(tracker.latestSnapshot(caseId).score()).isCloseTo(0.8, within(0.001));
  }

  @Test
  void deltaComputesDifference() {
    registry.register(area("stability", 0.8));
    var policy = new HealthPolicy(null, null, null, null, null, null);
    tracker.refresh(caseId, policy);
    assertThat(tracker.delta(caseId, 1440)).isCloseTo(0.0, within(0.001));
  }

  @Test
  void emptyRegistryReturnsZero() {
    var policy = new HealthPolicy(null, null, null, null, null, null);
    assertThat(tracker.computeScore(caseId, policy)).isEqualTo(0.0);
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
