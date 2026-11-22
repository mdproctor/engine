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
package io.casehub.engine.rest.service;

import io.casehub.engine.plan.execution.CasePlanModelSnapshot;
import io.casehub.engine.plan.execution.DagResultSnapshot;
import io.casehub.engine.plan.execution.ExecutionStateSnapshot;
import io.casehub.engine.plan.execution.NodeStateSnapshot;
import io.casehub.engine.plan.snapshot.DagResultView;
import io.casehub.engine.plan.snapshot.ExecutionStateView;
import io.casehub.engine.plan.snapshot.PlanModelView;
import java.util.Map;
import java.util.stream.Collectors;

final class PlanViewMapper {

  private PlanViewMapper() {}

  static PlanModelView toPlanModelView(CasePlanModelSnapshot s) {
    return new PlanModelView(
        s.caseId(),
        s.agenda() == null
            ? null
            : s.agenda().stream()
                .map(
                    a ->
                        new PlanModelView.AgendaItemView(
                            a.planItemId(),
                            a.bindingName(),
                            a.status(),
                            a.description(),
                            a.targetType()))
                .toList(),
        s.focus(),
        s.focusRationale(),
        s.resourceBudget(),
        s.subCases() == null
            ? null
            : s.subCases().stream()
                .map(
                    sc ->
                        new PlanModelView.SubCaseView(
                            sc.caseDefinition(), sc.namespace(), sc.status()))
                .toList(),
        s.compounds() == null
            ? null
            : s.compounds().stream()
                .map(
                    c ->
                        new PlanModelView.CompoundStatusView(
                            c.id(),
                            c.name(),
                            c.status(),
                            c.childCount(),
                            c.completedCount(),
                            c.completion()))
                .toList(),
        s.timestamp());
  }

  static DagResultView toDagResultView(DagResultSnapshot s) {
    return new DagResultView(
        s.nodeStates() == null
            ? null
            : s.nodeStates().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> toNodeStateView(e.getValue()))),
        s.completedResults(),
        s.allSucceeded(),
        s.elapsed(),
        s.timestamp(),
        s.nodeDurationsMs());
  }

  private static DagResultView.NodeStateView toNodeStateView(NodeStateSnapshot ns) {
    return new DagResultView.NodeStateView(
        ns.kind(),
        ns.reason(),
        ns.contingencyResult() == null ? null : toDagResultView(ns.contingencyResult()));
  }

  static ExecutionStateView toExecutionStateView(ExecutionStateSnapshot s) {
    return new ExecutionStateView(
        s.executionId(),
        s.state(),
        s.model() == null
            ? null
            : new ExecutionStateView.ExecutionModelView(
                s.model().pattern(),
                s.model().routingStrategy(),
                s.model().decompositionStrategy(),
                s.model().activationStrategy(),
                s.model().aggregationStrategy(),
                s.model().terminationStrategy(),
                s.model().failurePolicy() == null
                    ? null
                    : new ExecutionStateView.FailurePolicyView(
                        s.model().failurePolicy().routingFailureAction(),
                        s.model().failurePolicy().aggregationFailureAction(),
                        s.model().failurePolicy().agentRetryPolicy() == null
                            ? null
                            : new ExecutionStateView.AgentRetryPolicyView(
                                s.model().failurePolicy().agentRetryPolicy().maxRetries(),
                                s.model().failurePolicy().agentRetryPolicy().backoffStrategy(),
                                s.model().failurePolicy().agentRetryPolicy().initialDelayMs()))),
        s.result(),
        s.activeAgents() == null
            ? null
            : s.activeAgents().stream()
                .map(a -> new ExecutionStateView.AgentRefView(a.id(), a.type(), a.name()))
                .toList(),
        s.completedAgents() == null
            ? null
            : s.completedAgents().stream()
                .map(
                    a ->
                        new ExecutionStateView.AgentResultView(
                            new ExecutionStateView.AgentRefView(
                                a.agentRef().id(), a.agentRef().type(), a.agentRef().name()),
                            a.status(),
                            a.detail(),
                            a.error(),
                            a.duration()))
                .toList(),
        s.iteration(),
        s.startedAt(),
        s.completedAt());
  }
}
