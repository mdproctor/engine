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
package io.casehub.engine.plan.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.TaskStatus;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AdaptationContext(
    UUID caseId,
    String tenancyId,
    String compoundId,
    String goalName,
    List<CompletedStep> completedSteps,
    List<PlanStepDescriptor> pendingSteps,
    List<PlanStepDescriptor> runningSteps,
    JsonNode currentContext,
    CaseDefinition definition,
    TaskStatus latestStatus,
    String latestBindingName,
    int adaptationGeneration) {

  public AdaptationContext {
    Objects.requireNonNull(caseId, "caseId");
    Objects.requireNonNull(tenancyId, "tenancyId");
    Objects.requireNonNull(compoundId, "compoundId");
    Objects.requireNonNull(goalName, "goalName");
    completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
    pendingSteps = pendingSteps == null ? List.of() : List.copyOf(pendingSteps);
    runningSteps = runningSteps == null ? List.of() : List.copyOf(runningSteps);
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(latestStatus, "latestStatus");
    Objects.requireNonNull(latestBindingName, "latestBindingName");
  }
}
