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

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record ObservationContext(
    JsonNode snapshot,
    Set<String> changedKeys,
    List<ContextSnapshot> history,
    String agentId,
    String tenancyId,
    UUID caseId,
    java.util.Map<String, io.casehub.api.model.signal.PerceivedSignal> signals) {

  public ObservationContext(
      JsonNode snapshot,
      Set<String> changedKeys,
      List<ContextSnapshot> history,
      String agentId,
      String tenancyId,
      UUID caseId) {
    this(snapshot, changedKeys, history, agentId, tenancyId, caseId, java.util.Map.of());
  }
}
