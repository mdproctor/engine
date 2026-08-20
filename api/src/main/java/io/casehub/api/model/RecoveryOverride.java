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

import java.util.Set;

public record RecoveryOverride(
    Integer maxRetries,
    Integer maxRerouteAttempts,
    RecoveryLevel maxLevel,
    boolean skipRecovery,
    Set<OutcomeType> skipRecoveryFor) {
  public RecoveryOverride {
    if (skipRecoveryFor == null) skipRecoveryFor = Set.of();
  }

  public static RecoveryOverride skip() {
    return new RecoveryOverride(null, null, null, true, Set.of());
  }

  public int effectiveMaxRetries(RecoveryPolicy policy) {
    return maxRetries != null ? maxRetries : policy.maxRetries();
  }

  public int effectiveMaxRerouteAttempts(RecoveryPolicy policy) {
    return maxRerouteAttempts != null ? maxRerouteAttempts : policy.maxRerouteAttempts();
  }

  public RecoveryLevel effectiveMaxLevel() {
    return maxLevel != null ? maxLevel : RecoveryLevel.FUNDAMENTAL;
  }
}
