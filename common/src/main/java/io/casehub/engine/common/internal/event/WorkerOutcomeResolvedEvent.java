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

import io.casehub.engine.common.internal.model.CaseInstance;

/**
 * Published by {@code WorkflowExecutionCompletedHandler} when a worker returns a non-success {@link
 * io.casehub.api.model.WorkerOutcome}. Consumed by {@code WorkerOutcomeResolvedHandler} in the
 * blackboard module for PlanItem lifecycle management.
 *
 * <p>{@code caseInstance} is included so the handler can construct {@link CaseContextChangedEvent}
 * for CONTEXT_CHANGED publishing — the handler is the only safe site for this publish because the
 * PlanItem must be FAULTED before binding re-evaluation.
 *
 * @param caseInstance the case instance (carries context for CONTEXT_CHANGED construction)
 * @param workerId the worker that produced the non-success outcome
 * @param bindingName the binding that dispatched the worker (PlanItem lookup key)
 * @param capabilityName the capability that was routed
 * @param disposition pre-resolved action from OutcomePolicy (REROUTE, EXHAUSTED, or FAULT)
 */
public record WorkerOutcomeResolvedEvent(
    CaseInstance caseInstance,
    String workerId,
    String bindingName,
    String capabilityName,
    OutcomeDisposition disposition) {}
