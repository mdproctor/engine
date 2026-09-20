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
import java.util.List;

public record ImprovementBudget(
    @Nullable Integer maxConcurrent,
    @Nullable Integer maxPerDay,
    @Nullable Integer cooldownMinutes,
    @Nullable List<String> allowedRepos,
    @Nullable List<String> deniedPaths,
    @Nullable Boolean requireReview,
    @Nullable Integer maxPRSize) {

  public int effectiveMaxConcurrent() {
    return maxConcurrent != null ? maxConcurrent : 3;
  }

  public int effectiveMaxPerDay() {
    return maxPerDay != null ? maxPerDay : 10;
  }

  public int effectiveCooldownMinutes() {
    return cooldownMinutes != null ? cooldownMinutes : 30;
  }

  public List<String> effectiveAllowedRepos() {
    return allowedRepos != null ? allowedRepos : List.of();
  }

  public List<String> effectiveDeniedPaths() {
    return deniedPaths != null ? deniedPaths : List.of();
  }

  public boolean effectiveRequireReview() {
    return requireReview != null ? requireReview : true;
  }

  public int effectiveMaxPRSize() {
    return maxPRSize != null ? maxPRSize : 500;
  }
}
