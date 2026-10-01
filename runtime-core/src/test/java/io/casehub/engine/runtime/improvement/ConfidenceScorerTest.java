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

import io.casehub.api.model.stigmergy.HealthScoreSnapshot;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfidenceScorerTest {

  private ConfidenceScorer scorer;
  private UUID caseId;
  private UUID improvementCaseId;

  @BeforeEach
  void setUp() {
    scorer = new ConfidenceScorer();
    caseId = UUID.randomUUID();
    improvementCaseId = UUID.randomUUID();
  }

  @Test
  void noSignalsReturnsZero() {
    var before = snapshot(0.8, Map.of("stability", 0.8));
    var after = snapshot(0.8, Map.of("stability", 0.8));
    assertThat(scorer.score(caseId, improvementCaseId, before, after)).isEqualTo(0.0);
  }

  @Test
  void regressionWithinWindowAddsConfidence() {
    var before = snapshot(0.8, Map.of("stability", 0.8));
    var after = snapshot(0.5, Map.of("stability", 0.5));
    assertThat(scorer.score(caseId, improvementCaseId, before, after))
        .isCloseTo(0.2, within(0.001));
  }

  @Test
  void multipleAreasDegradedAddsConfidence() {
    var before = snapshot(0.8, Map.of("stability", 0.8, "performance", 0.7));
    var after = snapshot(0.5, Map.of("stability", 0.5, "performance", 0.4));
    double score = scorer.score(caseId, improvementCaseId, before, after);
    assertThat(score).isCloseTo(0.3, within(0.001));
  }

  @Test
  void scoreClampedToZeroOne() {
    var before = snapshot(1.0, Map.of("a", 1.0, "b", 1.0, "c", 1.0, "d", 1.0));
    var after = snapshot(0.0, Map.of("a", 0.0, "b", 0.0, "c", 0.0, "d", 0.0));
    double score = scorer.score(caseId, improvementCaseId, before, after);
    assertThat(score).isGreaterThanOrEqualTo(0.0);
    assertThat(score).isLessThanOrEqualTo(1.0);
  }

  private HealthScoreSnapshot snapshot(double score, Map<String, Double> components) {
    return new HealthScoreSnapshot(score, Instant.now(), components);
  }
}
