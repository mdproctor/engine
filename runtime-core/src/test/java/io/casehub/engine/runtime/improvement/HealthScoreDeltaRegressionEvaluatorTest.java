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

import io.casehub.api.model.stigmergy.HealthScoreSnapshot;
import io.casehub.api.model.stigmergy.RegressionVerdict;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HealthScoreDeltaRegressionEvaluatorTest {

  private final ConfidenceScorer scorer = new ConfidenceScorer();
  private final HealthScoreDeltaRegressionEvaluator evaluator =
      new HealthScoreDeltaRegressionEvaluator(scorer);
  private final UUID caseId = UUID.randomUUID();

  @Test
  void evaluatorIdAndDomainId() {
    assertThat(evaluator.evaluatorId()).isEqualTo("health-score-delta");
    assertThat(evaluator.domainId()).isEqualTo("code-evolution");
  }

  @Test
  void negativeDeltaReturnsDetected() {
    var baseline =
        new HealthScoreSnapshot(0.8, Instant.now(), Map.of("area-a", 0.9, "area-b", 0.7));
    var current = new HealthScoreSnapshot(0.5, Instant.now(), Map.of("area-a", 0.4, "area-b", 0.3));

    var verdict = evaluator.evaluate(caseId, baseline, current, "dependency-update");
    assertThat(verdict).isInstanceOf(RegressionVerdict.Detected.class);
    var detected = (RegressionVerdict.Detected) verdict;
    assertThat(detected.confidence()).isGreaterThan(0.0);
  }

  @Test
  void positiveDeltaReturnsNoRegression() {
    var baseline = new HealthScoreSnapshot(0.5, Instant.now(), Map.of("area-a", 0.5));
    var current = new HealthScoreSnapshot(0.8, Instant.now(), Map.of("area-a", 0.8));

    var verdict = evaluator.evaluate(caseId, baseline, current, "dependency-update");
    assertThat(verdict).isInstanceOf(RegressionVerdict.NoRegression.class);
  }

  @Test
  void zeroDeltaReturnsNoRegression() {
    var baseline = new HealthScoreSnapshot(0.7, Instant.now(), Map.of("area-a", 0.7));
    var current = new HealthScoreSnapshot(0.7, Instant.now(), Map.of("area-a", 0.7));

    var verdict = evaluator.evaluate(caseId, baseline, current, "lint-fix");
    assertThat(verdict).isInstanceOf(RegressionVerdict.NoRegression.class);
  }
}
