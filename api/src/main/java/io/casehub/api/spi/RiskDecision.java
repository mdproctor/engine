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
import java.time.Duration;
import org.jspecify.annotations.Nullable;

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

  record GateRequired(
      String reason,
      boolean reversible,
      CandidateSetStrategy candidateGroups,
      Duration expiresIn,
      String scope,
      @Nullable Class<?> resolutionType,
      @Nullable QuorumConfig quorum)
      implements RiskDecision {}
}
