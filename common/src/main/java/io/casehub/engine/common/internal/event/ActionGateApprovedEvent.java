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

import java.util.UUID;

/**
 * Published on {@link EventBusAddresses#ACTION_GATE_APPROVED} by {@code
 * ActionGateCompletionApplier} (work-adapter) when a gate WorkItem is COMPLETED.
 *
 * <p>Consumed by {@code ActionGateApprovedHandler} in the engine runtime, which re-fires {@link
 * WorkflowExecutionCompleted} with {@code plannedAction=null} so the normal completion machinery
 * applies the deferred output, marks the PlanItem COMPLETED, and fires CONTEXT_CHANGED.
 *
 * <p>{@code workItemResolution} is the raw resolution JSON from the WorkItem — typically contains
 * approver notes. {@code approvedBy} is sourced from {@code WorkItem.assignee}; may be null if the
 * WorkItem was completed without an explicit claim.
 *
 * @param caseId the case this gate belongs to
 * @param tenancyId the tenant owning the case
 * @param gateId the gate identifier
 * @param workItemResolution raw resolution JSON from the WorkItem
 * @param approvedBy the user who approved the gate, or null
 */
public record ActionGateApprovedEvent(
    UUID caseId, String tenancyId, long gateId, String workItemResolution, String approvedBy) {}
