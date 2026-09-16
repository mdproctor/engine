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
package io.casehub.api.spi.observation;

public record RuleConfig(int maxRulesPerCase, int maxActionsPerCycle, int ruleEvaluationTimeoutMs) {

  public static final int DEFAULT_MAX_RULES = 50;
  public static final int DEFAULT_MAX_ACTIONS = 100;
  public static final int DEFAULT_TIMEOUT_MS = 100;

  public RuleConfig {
    if (maxRulesPerCase < 1)
      throw new IllegalArgumentException("maxRulesPerCase must be >= 1, got: " + maxRulesPerCase);
    if (maxActionsPerCycle < 1)
      throw new IllegalArgumentException(
          "maxActionsPerCycle must be >= 1, got: " + maxActionsPerCycle);
    if (ruleEvaluationTimeoutMs < 1)
      throw new IllegalArgumentException(
          "ruleEvaluationTimeoutMs must be >= 1, got: " + ruleEvaluationTimeoutMs);
  }

  public static RuleConfig defaults() {
    return new RuleConfig(DEFAULT_MAX_RULES, DEFAULT_MAX_ACTIONS, DEFAULT_TIMEOUT_MS);
  }
}
