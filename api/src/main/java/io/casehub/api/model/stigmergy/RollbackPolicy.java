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
package io.casehub.api.model.stigmergy;

import jakarta.annotation.Nullable;

public record RollbackPolicy(
    @Nullable Double autoRevertThreshold,
    @Nullable Double pauseThreshold,
    @Nullable Boolean requireReviewForRevert,
    @Nullable Boolean pauseCategoryOnRegression,
    @Nullable Integer regressionWindowMinutes,
    @Nullable Integer sustainedFailureCount) {

  public double effectiveAutoRevertThreshold() {
    return autoRevertThreshold != null ? autoRevertThreshold : 0.9;
  }

  public double effectivePauseThreshold() {
    return pauseThreshold != null ? pauseThreshold : 0.5;
  }

  public boolean effectiveRequireReviewForRevert() {
    return requireReviewForRevert != null ? requireReviewForRevert : true;
  }

  public boolean effectivePauseCategoryOnRegression() {
    return pauseCategoryOnRegression != null ? pauseCategoryOnRegression : true;
  }

  public int effectiveRegressionWindowMinutes() {
    return regressionWindowMinutes != null ? regressionWindowMinutes : 60;
  }

  public int effectiveSustainedFailureCount() {
    return sustainedFailureCount != null ? sustainedFailureCount : 2;
  }
}
