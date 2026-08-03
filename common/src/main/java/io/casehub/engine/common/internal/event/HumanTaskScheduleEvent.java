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
package io.casehub.engine.common.internal.event;

import io.casehub.api.model.HumanTaskTarget;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record HumanTaskScheduleEvent(
    UUID caseId,
    String tenancyId,
    String bindingName,
    HumanTaskTarget target,
    Map<String, Object> inputData,
    String payloadTypeName,
    String resolutionTypeName,
    Set<String> resolvedCandidateGroups,
    Set<String> resolvedCandidateUsers,
    Instant caseBudgetDeadline,
    Instant expiresAtDeadline,
    String resolvedTitle,
    String resolvedScope,
    java.time.Duration resolvedExpiresIn,
    java.util.List<io.casehub.api.spi.routing.RetrievedExperience> experiences,
    Map<String, Double> candidateScores,
    com.fasterxml.jackson.databind.JsonNode activationContext) {}
