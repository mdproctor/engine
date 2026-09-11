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
package io.casehub.engine.common.internal.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.engine.common.internal.history.EventLog;
import java.util.List;
import java.util.Objects;

public final class AdaptationCostComputer {

  private AdaptationCostComputer() {}

  public static AdaptationCostSummary computeForCompound(
      List<EventLog> adaptationEvents, String compoundId) {
    int count = 0;
    int totalProduced = 0;
    int totalObsoleted = 0;
    for (EventLog event : adaptationEvents) {
      JsonNode meta = event.getMetadata();
      if (meta == null) continue;
      String eventCompound = meta.has("compoundId") ? meta.get("compoundId").asText() : null;
      if (!Objects.equals(compoundId, eventCompound)) continue;
      count++;
      totalProduced += meta.has("newStepCount") ? meta.get("newStepCount").asInt() : 0;
      totalObsoleted += meta.has("previousStepCount") ? meta.get("previousStepCount").asInt() : 0;
    }
    return new AdaptationCostSummary(count, totalProduced, totalObsoleted);
  }
}
