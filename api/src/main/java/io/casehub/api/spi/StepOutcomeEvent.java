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
package io.casehub.api.spi;

import io.casehub.api.spi.routing.RoutingOutcome;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Outcome event fired by the engine after each worker execution step completes — on both success
 * and failure paths. Delivered to all {@link StepOutcomeObserver} beans discovered via CDI.
 *
 * <p>{@code contextSnapshot} is the working layer at step execution time — on the success path,
 * captured <em>before</em> output application (the conditions under which the decision was made,
 * not the world after execution). On the failure path, captured at failure handling time (no output
 * was applied).
 *
 * <p>Refs casehubio/engine#1050.
 *
 * @param caseId case instance UUID
 * @param tenancyId tenant identifier owning the case
 * @param caseType case definition name — consumer uses this to find their CaseDefinition/CbrConfig
 * @param bindingName the case definition binding that dispatched the worker
 * @param capabilityName the capability targeted by this binding; nullable for JudgmentTarget traces
 * @param workerName the worker that executed
 * @param outcome the routing outcome (SUCCESS or FAILURE)
 * @param contextSnapshot working-layer context at step execution time; non-null, may be empty
 * @param executionDuration wall-clock duration of the worker execution; nullable
 */
public record StepOutcomeEvent(
    UUID caseId,
    String tenancyId,
    String caseType,
    String bindingName,
    @Nullable String capabilityName,
    String workerName,
    RoutingOutcome outcome,
    Map<String, Object> contextSnapshot,
    @Nullable Duration executionDuration) {}
