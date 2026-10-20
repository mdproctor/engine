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

import io.casehub.api.acl.EngineResourceTypes;
import io.casehub.api.engine.EntityNotFoundException;
import io.casehub.api.model.Binding;
import io.casehub.api.model.BindingTarget;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseCompletion;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CloudEventTrigger;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ExtensionTarget;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.PredicateBasedCompletion;
import io.casehub.api.model.ScheduleTrigger;
import io.casehub.api.model.ScopeActivatedTrigger;
import io.casehub.api.model.SignalTarget;
import io.casehub.api.model.SubCaseTarget;
import io.casehub.api.model.Trigger;
import io.casehub.api.view.BindingView;
import io.casehub.api.view.CaseDefinitionPage;
import io.casehub.api.view.CaseDefinitionView;
import io.casehub.api.view.CompletionView;
import io.casehub.api.view.CoordinationPipelineView;
import io.casehub.api.view.GoalView;
import io.casehub.api.view.MilestoneView;
import io.casehub.api.view.WorkerView;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseMetaModelRepository;
import io.casehub.engine.common.spi.query.CaseDefinitionQuery;
import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AccessDeniedException;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.ResourceId;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.worker.api.Capability;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CaseDefinitionService {

  private final CaseMetaModelRepository metaModelRepository;
  private final CaseDefinitionRegistry definitionRegistry;
  private final CurrentPrincipal currentPrincipal;
  private final AccessControlProvider accessControlProvider;

  public CaseDefinitionService(
      CaseMetaModelRepository metaModelRepository,
      CaseDefinitionRegistry definitionRegistry,
      CurrentPrincipal currentPrincipal,
      AccessControlProvider accessControlProvider) {
    this.metaModelRepository = metaModelRepository;
    this.definitionRegistry = definitionRegistry;
    this.currentPrincipal = currentPrincipal;
    this.accessControlProvider = accessControlProvider;
  }

  private static String expressionString(ExpressionEvaluator eval) {
    return eval != null ? eval.expression() : null;
  }

  private static String targetType(BindingTarget target) {
    return switch (target) {
      case CapabilityTarget ignored -> "capability";
      case SubCaseTarget ignored -> "subCase";
      case JudgmentTarget ignored -> "judgment";
      case SignalTarget ignored -> "signal";
      case HumanTaskTarget ignored -> "humanTask";
      case ExtensionTarget ignored -> "extension";
    };
  }

  private static String targetName(BindingTarget target) {
    return switch (target) {
      case CapabilityTarget ct -> ct.capability().name();
      case SubCaseTarget st -> st.subCase().name();
      case JudgmentTarget jt -> jt.title();
      case SignalTarget ignored -> null;
      case HumanTaskTarget ignored -> null;
      case ExtensionTarget ignored -> null;
    };
  }

  private static String triggerType(Trigger trigger) {
    return switch (trigger) {
      case ContextChangeTrigger ignored -> "contextChange";
      case ScheduleTrigger ignored -> "schedule";
      case ScopeActivatedTrigger ignored -> "scopeActivated";
      case CloudEventTrigger ignored -> "cloudEvent";
    };
  }

  private static String triggerExpression(Trigger trigger) {
    return switch (trigger) {
      case ContextChangeTrigger ct -> expressionString(ct.getFilter());
      case ScheduleTrigger st ->
          st.getCron() != null
              ? st.getCron()
              : (st.getDelay() != null ? st.getDelay().toString() : null);
      case ScopeActivatedTrigger ignored -> null;
      case CloudEventTrigger ce -> ce.getType();
    };
  }

  public CaseDefinitionPage listDefinitions(String tenancyId, Integer offset, Integer limit) {
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    int page = offset != null ? offset : 0;
    int size = limit != null ? limit : 20;
    var query = CaseDefinitionQuery.builder().page(page).size(size).build();
    var metas = metaModelRepository.query(query, resolvedTenancyId);
    String actorId = currentPrincipal.actorId();
    var items =
        metas.stream()
            .filter(
                meta ->
                    accessControlProvider.canAccess(
                        actorId,
                        new ResourceId(
                            EngineResourceTypes.CASE_DEFINITION,
                            meta.getNamespace() + "/" + meta.getName() + "/" + meta.getVersion()),
                        AclAction.READ))
            .map(
                meta -> {
                  CaseDefinition def = definitionRegistry.getCaseDefinition(meta);
                  return mapDefinition(meta, def);
                })
            .filter(Objects::nonNull)
            .toList();
    return new CaseDefinitionPage(items, items.size(), false);
  }

  public List<CaseDefinitionView> getDefinitionsByName(
      String namespace, String name, String tenancyId) {
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    String actorId = currentPrincipal.actorId();
    var query = CaseDefinitionQuery.builder().namespace(namespace).name(name).build();
    return metaModelRepository.query(query, resolvedTenancyId).stream()
        .filter(
            meta ->
                accessControlProvider.canAccess(
                    actorId,
                    new ResourceId(
                        EngineResourceTypes.CASE_DEFINITION,
                        meta.getNamespace() + "/" + meta.getName() + "/" + meta.getVersion()),
                    AclAction.READ))
        .map(
            meta -> {
              CaseDefinition def = definitionRegistry.getCaseDefinition(meta);
              return mapDefinition(meta, def);
            })
        .filter(Objects::nonNull)
        .toList();
  }

  public CaseDefinitionView getDefinitionByKey(
      String namespace, String name, String version, String tenancyId) {
    var resolved = resolveDefinition(namespace, name, version);
    return mapDefinition(resolved.meta, resolved.def);
  }

  CaseDefinitionView mapDefinition(CaseMetaModel meta, CaseDefinition def) {
    if (def == null) {
      return null;
    }
    List<String> capabilities =
        def.getCapabilities() != null
            ? def.getCapabilities().stream().map(Capability::name).toList()
            : List.of();
    List<WorkerView> workers = mapWorkers(def);
    List<BindingView> bindings = mapBindings(def);
    List<MilestoneView> milestones = mapMilestones(def);
    List<GoalView> goals = mapGoals(def);
    CompletionView completion = mapCompletion(def.getCompletion());
    return new CaseDefinitionView(
        meta.getNamespace(),
        meta.getName(),
        meta.getVersion(),
        def.getTitle(),
        def.getSummary(),
        capabilities,
        workers,
        bindings,
        milestones,
        goals,
        completion);
  }

  private List<WorkerView> mapWorkers(CaseDefinition def) {
    if (def.getWorkers() == null) {
      return List.of();
    }
    return def.getWorkers().stream()
        .map(
            w ->
                new WorkerView(
                    w.name(),
                    w.capabilities() != null ? List.copyOf(w.capabilities()) : List.of(),
                    w.description(),
                    w.definitionRef()))
        .toList();
  }

  private List<BindingView> mapBindings(CaseDefinition def) {
    if (def.getBindings() == null) {
      return List.of();
    }
    return def.getBindings().stream().map(this::mapBinding).toList();
  }

  private BindingView mapBinding(Binding b) {
    String targetType = targetType(b.target());
    String targetName = targetName(b.target());
    String triggerType = b.getOn() != null ? triggerType(b.getOn()) : null;
    String triggerExpression = b.getOn() != null ? triggerExpression(b.getOn()) : null;
    String when = expressionString(b.getWhen());
    return new BindingView(
        b.getName(), targetType, targetName, triggerType, triggerExpression, when);
  }

  private List<MilestoneView> mapMilestones(CaseDefinition def) {
    if (def.getMilestones() == null) {
      return List.of();
    }
    return def.getMilestones().stream()
        .map(
            m ->
                new MilestoneView(
                    m.getName(),
                    m.getDescription(),
                    expressionString(m.getEntryCriteria()),
                    expressionString(m.getCompletionCriteria()),
                    m.getSlaDuration() != null ? m.getSlaDuration().toString() : null))
        .toList();
  }

  private List<GoalView> mapGoals(CaseDefinition def) {
    if (def.getGoals() == null) {
      return List.of();
    }
    return def.getGoals().stream()
        .map(
            g ->
                new GoalView(
                    g.getName(),
                    g.getDescription(),
                    expressionString(g.getCondition()),
                    g.getKind()))
        .toList();
  }

  private CompletionView mapCompletion(CaseCompletion completion) {
    if (completion == null) {
      return null;
    }
    if (completion instanceof PredicateBasedCompletion p) {
      return new CompletionView("predicate", expressionString(p.getDoneWhen()), null);
    }
    if (completion instanceof GoalBasedCompletion<?> g) {
      Map<String, String> goals = new LinkedHashMap<>();
      g.getGoals().forEach((k, v) -> goals.put(String.valueOf(k), v.toString()));
      return new CompletionView("goal-based", null, goals);
    }
    return new CompletionView(completion.getClass().getSimpleName(), null, null);
  }

  public CoordinationPipelineView getPipelineProjection(
      String namespace, String name, String version, String tenancyId) {
    var resolved = resolveDefinition(namespace, name, version);
    return new CoordinationPipelineProjection().project(resolved.def);
  }

  private ResolvedDefinition resolveDefinition(String namespace, String name, String version) {
    var meta =
        definitionRegistry
            .findByIdentity(namespace, name, version)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        String.format("No definition for %s/%s/%s", namespace, name, version)));
    String actorId = currentPrincipal.actorId();
    ResourceId rid =
        new ResourceId(EngineResourceTypes.CASE_DEFINITION, namespace + "/" + name + "/" + version);
    if (!accessControlProvider.canAccess(actorId, rid, AclAction.READ)) {
      throw new AccessDeniedException(actorId, rid, AclAction.READ);
    }
    CaseDefinition def = definitionRegistry.getCaseDefinition(meta);
    if (def == null) {
      throw new EntityNotFoundException(
          String.format("Definition body not found for %s/%s/%s", namespace, name, version));
    }
    return new ResolvedDefinition(meta, def);
  }

  private record ResolvedDefinition(CaseMetaModel meta, CaseDefinition def) {}
}
