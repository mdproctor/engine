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

public final class DivergenceScoreComputer {

  private DivergenceScoreComputer() {}

  public static double computeForCompound(
      List<EventLog> completionEntries, int windowSize, int adaptationGeneration) {

    List<EventLog> filtered =
        completionEntries.stream()
            .filter(
                e -> {
                  JsonNode meta = e.getMetadata();
                  if (meta == null || !meta.has("expectationValidation")) {
                    return false;
                  }
                  JsonNode validation = meta.get("expectationValidation");
                  int gen =
                      validation.has("adaptationGeneration")
                          ? validation.get("adaptationGeneration").asInt()
                          : 0;
                  return gen == adaptationGeneration;
                })
            .toList();

    if (filtered.isEmpty()) {
      return 0.0;
    }

    List<EventLog> window =
        filtered.size() <= windowSize
            ? filtered
            : filtered.subList(filtered.size() - windowSize, filtered.size());

    double totalRatio = 0.0;
    for (EventLog entry : window) {
      JsonNode validation = entry.getMetadata().get("expectationValidation");
      totalRatio += validation.get("divergenceRatio").asDouble();
    }
    return totalRatio / window.size();
  }
}
