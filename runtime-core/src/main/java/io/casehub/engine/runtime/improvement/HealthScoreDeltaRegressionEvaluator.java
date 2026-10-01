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

import io.casehub.api.model.stigmergy.HealthScoreSnapshot;
import io.casehub.api.model.stigmergy.RegressionVerdict;
import io.casehub.api.spi.improvement.RegressionEvaluator;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class HealthScoreDeltaRegressionEvaluator implements RegressionEvaluator {

  private final ConfidenceScorer scorer;

  public HealthScoreDeltaRegressionEvaluator(ConfidenceScorer scorer) {
    this.scorer = scorer;
  }

  @Override
  public String evaluatorId() {
    return "health-score-delta";
  }

  @Override
  public String domainId() {
    return "code-evolution";
  }

  @Override
  public RegressionVerdict evaluate(
      UUID caseId, HealthScoreSnapshot baseline, HealthScoreSnapshot current, String category) {
    double confidence = scorer.score(caseId, null, baseline, current);
    if (confidence > 0.0) {
      return new RegressionVerdict.Detected(confidence, "Health score regression detected");
    }
    return new RegressionVerdict.NoRegression();
  }
}
