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

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class ConfidenceScorer {

  public double score(
      UUID caseId,
      UUID improvementCaseId,
      HealthScoreTracker.HealthSnapshot before,
      HealthScoreTracker.HealthSnapshot after) {
    double confidence = 0.0;

    if (regressionWithinWindow(before, after)) {
      confidence += 0.2;
    }
    if (multipleAreasDegraded(before, after)) {
      confidence += 0.1;
    }

    return Math.max(0.0, Math.min(1.0, confidence));
  }

  private boolean regressionWithinWindow(
      HealthScoreTracker.HealthSnapshot before, HealthScoreTracker.HealthSnapshot after) {
    return after.score() < before.score();
  }

  private boolean multipleAreasDegraded(
      HealthScoreTracker.HealthSnapshot before, HealthScoreTracker.HealthSnapshot after) {
    int degradedCount = 0;
    for (var entry : before.componentScores().entrySet()) {
      Double afterScore = after.componentScores().get(entry.getKey());
      if (afterScore != null && afterScore < entry.getValue()) {
        degradedCount++;
      }
    }
    return degradedCount >= 2;
  }
}
