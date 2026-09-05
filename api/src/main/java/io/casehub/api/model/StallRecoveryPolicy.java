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

import io.casehub.qhorus.api.watchdog.WatchdogConditionType;
import java.util.Map;

public record StallRecoveryPolicy(
    boolean enabled,
    String classifierId,
    Map<WatchdogConditionType, StallRecoveryAction> conditionActions,
    StallRecoveryAction defaultAction) {

  public static final StallRecoveryPolicy DEFAULT =
      new StallRecoveryPolicy(false, "policy-lookup", Map.of(), StallRecoveryAction.NOTIFY);

  public StallRecoveryPolicy {
    conditionActions = conditionActions != null ? Map.copyOf(conditionActions) : Map.of();
    if (defaultAction == null) defaultAction = StallRecoveryAction.NOTIFY;
    if (classifierId == null || classifierId.isBlank()) classifierId = "policy-lookup";
  }
}
