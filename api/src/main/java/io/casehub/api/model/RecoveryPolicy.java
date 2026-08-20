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
package io.casehub.api.model;

public record RecoveryPolicy(
    int maxRetries,
    int maxRerouteAttempts,
    String classifierId,
    String revisionStrategyId,
    String replanStrategyId,
    boolean enabled) {
  public static final RecoveryPolicy DEFAULT =
      new RecoveryPolicy(3, 3, "heuristic", "forward-replan", "llm", true);

  public static final RecoveryPolicy DISABLED =
      new RecoveryPolicy(0, 0, "heuristic", "forward-replan", "llm", false);
}
