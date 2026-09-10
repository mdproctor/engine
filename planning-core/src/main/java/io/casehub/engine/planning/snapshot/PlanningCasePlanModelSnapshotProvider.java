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
package io.casehub.engine.planning.snapshot;

import io.casehub.api.model.BindingTarget;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.ExtensionTarget;
import io.casehub.api.model.HumanRoutingConfig;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.SignalTarget;
import io.casehub.api.model.SubCaseTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.plan.execution.AgendaItemSnapshot;
import io.casehub.engine.plan.execution.CasePlanModelSnapshot;
import io.casehub.engine.plan.execution.CasePlanModelSnapshotProvider;
import io.casehub.engine.plan.execution.CompoundStatusSnapshot;
import io.casehub.engine.plan.execution.SubCaseSnapshotRecord;
import io.casehub.engine.plan.snapshot.CompletionSemanticsSnapshot;
import io.casehub.engine.plan.snapshot.CompoundItemSnapshot;
import io.casehub.engine.plan.snapshot.PlanItemDefinitionSnapshot;
import io.casehub.engine.plan.snapshot.PrimitiveItemSnapshot;
import io.casehub.engine.planning.plan.CompletionSemantics;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class PlanningCasePlanModelSnapshotProvider implements CasePlanModelSnapshotProvider {

  private final BlackboardRegistry registry;

  @Inject
  public PlanningCasePlanModelSnapshotProvider(BlackboardRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Optional<CasePlanModelSnapshot> getSnapshot(UUID caseId, String tenancyId) {
    return registry
        .get(caseId, tenancyId)
        .map(
            plan -> {
              var agenda =
                  plan.getAgenda().stream()
                      .map(
                          item ->
                              new AgendaItemSnapshot(
                                  item.id(),
                                  item.getBindingName(),
                                  item.getStatus().name(),
                                  item.getDescription(),
                                  mapTargetType(item.getTarget())))
                      .toList();

              var subCases =
                  plan.getSubCases().stream()
                      .map(sc -> new SubCaseSnapshotRecord(sc.name(), sc.namespace(), null))
                      .toList();

              var compounds =
                  plan.getAllCompounds().stream()
                      .map(
                          c -> {
                            TaskStatus status = plan.getDefinitionStatus(c.id());
                            Set<String> children = plan.getChildrenOf(c.id());
                            long completed =
                                children.stream()
                                    .map(plan::getDefinitionStatus)
                                    .filter(s -> s == TaskStatus.COMPLETED)
                                    .count();
                            return new CompoundStatusSnapshot(
                                c.id(),
                                c.name(),
                                status != null ? status.name() : "PENDING",
                                children.size(),
                                (int) completed,
                                toCompletionSnapshot(c.completion()));
                          })
                      .toList();

              return new CasePlanModelSnapshot(
                  caseId,
                  agenda,
                  plan.getFocus().orElse(null),
                  plan.getFocusRationale().orElse(null),
                  plan.getResourceBudget(),
                  subCases,
                  compounds,
                  Instant.now());
            });
  }

  @Override
  public List<PlanItemDefinitionSnapshot> getDefinitions(UUID caseId, String tenancyId) {
    return registry
        .get(caseId, tenancyId)
        .map(
            plan ->
                plan.getAllCompounds().stream()
                    .map(c -> (PlanItemDefinitionSnapshot) toDefinitionSnapshot(c))
                    .toList())
        .orElse(List.of());
  }

  private PlanItemDefinitionSnapshot toDefinitionSnapshot(PlanItemDefinition def) {
    return switch (def) {
      case PlanItemDefinition.Primitive p ->
          new PrimitiveItemSnapshot(
              p.id(),
              p.name(),
              p.executor() != null ? p.executor().name() : null,
              p.executor() != null ? p.executor().description() : null,
              expressionToString(p.entryCondition()));
      case PlanItemDefinition.Compound c ->
          new CompoundItemSnapshot(
              c.id(),
              c.name(),
              c.children().stream().map(this::toDefinitionSnapshot).toList(),
              c.planningStrategy(),
              toCompletionSnapshot(c.completion()),
              c.dispatchMode().name(),
              expressionToString(c.entryCondition()),
              expressionToString(c.exitCondition()),
              c.repeatable(),
              toScopedBindingsMap(c.scopedBindings()));
    };
  }

  private static CompletionSemanticsSnapshot toCompletionSnapshot(CompletionSemantics cs) {
    return switch (cs) {
      case CompletionSemantics.All a -> new CompletionSemanticsSnapshot.AllSnapshot();
      case CompletionSemantics.MOfN m -> new CompletionSemanticsSnapshot.MOfNSnapshot(m.m());
      case CompletionSemantics.FirstWins f -> new CompletionSemanticsSnapshot.FirstWinsSnapshot();
    };
  }

  private static String expressionToString(ExpressionEvaluator eval) {
    if (eval == null) return null;
    if (eval instanceof JQExpressionEvaluator jq) return jq.expression();
    return "<lambda>";
  }

  private static Map<String, String> toScopedBindingsMap(
      Map<String, io.casehub.api.model.Participation> bindings) {
    if (bindings == null || bindings.isEmpty()) return Map.of();
    Map<String, String> result = new LinkedHashMap<>();
    bindings.forEach((k, v) -> result.put(k, v.name()));
    return Map.copyOf(result);
  }

  private static String mapTargetType(BindingTarget target) {
    if (target == null) {
      return "WORKER";
    }
    return switch (target) {
      case CapabilityTarget c -> "WORKER";
      case JudgmentTarget jt ->
          jt.routingConfig() instanceof HumanRoutingConfig ? "HUMAN" : "WORKER";
      case SubCaseTarget s -> "COMPOSED";
      case HumanTaskTarget h -> "HUMAN";
      case SignalTarget sg -> "WORKER";
      case ExtensionTarget e -> "EXTERNAL";
    };
  }
}
