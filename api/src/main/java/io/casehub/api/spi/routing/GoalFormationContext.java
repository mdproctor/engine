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
package io.casehub.api.spi.routing;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.RetrievedMemory;
import io.casehub.eidos.api.AgentGoal;
import java.util.List;
import java.util.Objects;

public record GoalFormationContext(
    String agentId,
    String tenancyId,
    List<String> reflectionInsights,
    List<AgentGoal> existingGoals,
    List<RetrievedMemory> recentMemories,
    int remainingCapacity,
    CaseDefinition definition) {
  public GoalFormationContext {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(tenancyId, "tenancyId must not be null");
    Objects.requireNonNull(reflectionInsights, "reflectionInsights must not be null");
    reflectionInsights = List.copyOf(reflectionInsights);
    Objects.requireNonNull(existingGoals, "existingGoals must not be null");
    existingGoals = List.copyOf(existingGoals);
    Objects.requireNonNull(recentMemories, "recentMemories must not be null");
    recentMemories = List.copyOf(recentMemories);
  }
}
