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

import io.casehub.api.spi.routing.CandidateSetStrategy;
import io.casehub.worker.api.PlannedAction;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * The outcome of {@link ActionRiskClassifier#classify(PlannedAction)}.
 *
 * <p>{@link Autonomous} — proceed immediately; case advances as if no PlannedAction was declared.
 *
 * <p>{@link GateRequired} — pause the case and route to a human approver via a WorkItem. The engine
 * fires an {@code ActionGateScheduleEvent}; {@code casehub-work-engine-adapter} creates the
 * WorkItem. If the engine-adapter is absent the case stalls — see {@code
 * ActionGateDeploymentHealthCheck}.
 */
public sealed interface RiskDecision permits RiskDecision.Autonomous, RiskDecision.GateRequired {

  record Autonomous() implements RiskDecision {}

    /**
     * Gate the action pending human approval.
     *
     * <p>{@code candidateGroups} — null means no group restriction on the WorkItem. When multiple
     * classifiers both return {@code GateRequired}, the one with the fewest groups wins entirely —
     * union semantics are wrong for compliance (an MLRO and a physician are not interchangeable
     * approvers).
     *
     * <p>{@code reversible} — purely presentational. Shown to the approver as "This action cannot be
     * undone" when false. Does not affect engine routing or WorkItem creation.
     *
     * <p>{@code scope} — hierarchical path for SLA preference resolution on the gate WorkItem,
     * following the {@code Path.of("org", "app", "case-type")} convention (e.g. {@code
     * "casehubio/life/oversight"}). Passed directly to {@code WorkItemCreateRequest.scope}.
     *
     * <p>{@code resolutionType} — declares the expected type for the gate WorkItem's resolution.
     * Null means untyped (approver notes). Threaded as {@code resolutionTypeName} (String) through
     * {@code PendingActionGate}, {@code ActionGateScheduleEvent}, and {@code ActionGateApprovedEvent}.
     * {@code ActionGateApprovedHandler} validates via {@code BridgeResolver} and includes the typed
     * resolution in the {@code actionGateApproved} context entry.
     *
     * <p>If {@link ActionRiskClassifier#classify(PlannedAction)} throws, the engine uses a fail-safe
     * {@code GateRequired} with all nullable fields set to null.
     */
    record GateRequired(
            String reason,
            boolean reversible,
            CandidateSetStrategy candidateGroups,
            Duration expiresIn,
            String scope,
            @Nullable Class<?> resolutionType)
            implements RiskDecision {}
}
