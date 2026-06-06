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

import java.time.Duration;
import java.util.List;

/**
 * The outcome of {@link ActionRiskClassifier#classify(PlannedAction)}.
 *
 * <p>{@link Autonomous} — proceed immediately; case advances as if no PlannedAction was declared.
 *
 * <p>{@link GateRequired} — pause the case and route to a human approver via a WorkItem. The engine
 * fires an {@code ActionGateScheduleEvent}; {@code casehub-engine-work-adapter} creates the
 * WorkItem. If work-adapter is absent the case stalls — see {@code
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
   * <p>If {@link ActionRiskClassifier#classify(PlannedAction)} throws, the engine uses {@code new
   * GateRequired("Classifier error — manual review required", true, null, null, null)} as the
   * fail-safe default.
   */
  record GateRequired(
      String reason,
      boolean reversible,
      List<String> candidateGroups,
      Duration expiresIn,
      String scope)
      implements RiskDecision {}
}
