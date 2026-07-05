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

/**
 * Published on {@link EventBusAddresses#HUMAN_TASK_SCHEDULE} when a binding with {@link
 * HumanTaskTarget} is selected for execution.
 *
 * <p>{@code inputData} is pre-evaluated by {@code CaseContextChangedEventHandler} before publishing
 * — consumers receive the resolved payload, not the raw expression.
 *
 * <p>{@code caseBudgetDeadline} is the case-level PropagationContext deadline, or {@code null} if
 * no budget was set. The handler uses this to bound WorkItem {@code expiresAt} so a WorkItem cannot
 * outlive its parent case. See casehubio/parent#6.
 *
 * <p>{@code expiresAtDeadline} is an absolute deadline resolved from {@link
 * HumanTaskTarget#expiresAtExpression()} evaluated against the case context WORKING layer, or
 * {@code null} if no expression was set or evaluation failed. The handler folds this into the
 * earliest-deadline chain alongside {@code expiresIn} and {@code caseBudgetDeadline}. See
 * casehubio/engine#549, casehubio/clinical#83.
 *
 * <p>The binding name is the stable key for plan item lookup in the blackboard registry. See
 * engine#245.
 */
public record HumanTaskScheduleEvent(
    UUID caseId,
    String bindingName,
    HumanTaskTarget target,
    Map<String, Object> inputData,
    Set<String> resolvedCandidateGroups,
    Set<String> resolvedCandidateUsers,
    Instant caseBudgetDeadline,
    Instant expiresAtDeadline,
    String tenancyId) {}
