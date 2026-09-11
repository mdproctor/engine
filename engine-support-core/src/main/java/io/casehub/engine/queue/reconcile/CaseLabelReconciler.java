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
package io.casehub.engine.queue.reconcile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.queue.event.CaseQueueEvent;
import io.casehub.engine.queue.event.CaseQueueEventType;
import io.casehub.platform.api.label.LabelAction;
import io.casehub.platform.api.label.LabelRule;
import io.casehub.platform.api.view.CrossTenantSubjectViewStore;
import io.casehub.platform.api.view.SubjectViewEvent;
import io.casehub.platform.view.SubjectViewOrchestrator;
import java.util.function.Consumer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

public class CaseLabelReconciler {

  private static final Logger LOG = Logger.getLogger(CaseLabelReconciler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final CaseStatus[] ACTIVE_STATUSES = {
    CaseStatus.STARTING, CaseStatus.RUNNING, CaseStatus.WAITING, CaseStatus.SUSPENDED
  };

  private final CaseDefinitionRegistry definitionRegistry;
  private final CaseInstanceRepository caseInstanceRepository;
  private final SubjectViewOrchestrator views;
  private final CrossTenantSubjectViewStore crossTenantViewStore;
  private final Consumer<CaseQueueEvent> queueEventConsumer;

  public CaseLabelReconciler(
      CaseDefinitionRegistry definitionRegistry,
      CaseInstanceRepository caseInstanceRepository,
      SubjectViewOrchestrator views,
      CrossTenantSubjectViewStore crossTenantViewStore,
      Consumer<CaseQueueEvent> queueEventConsumer) {
    this.definitionRegistry = definitionRegistry;
    this.caseInstanceRepository = caseInstanceRepository;
    this.views = views;
    this.crossTenantViewStore = crossTenantViewStore;
    this.queueEventConsumer = queueEventConsumer;
  }

  public void reconcile() {
    List<String> tenancyIds = crossTenantViewStore.findDistinctTenancyIds();
    if (tenancyIds.isEmpty()) {
      LOG.debug("No tenancies with queue views — skipping label reconciliation");
      return;
    }

    int reconciledCount = 0;
    for (String tenancyId : tenancyIds) {
      for (CaseStatus status : ACTIVE_STATUSES) {
        List<CaseInstance> cases = caseInstanceRepository.findByStatus(status, tenancyId);
        for (CaseInstance instance : cases) {
          if (reconcileCase(instance, tenancyId)) {
            reconciledCount++;
          }
        }
      }
    }

    if (reconciledCount > 0) {
      LOG.infof("Label reconciliation complete — %d case(s) reconciled", reconciledCount);
    } else {
      LOG.debug("Label reconciliation complete — no cases needed reconciliation");
    }
  }

  private boolean reconcileCase(CaseInstance instance, String tenancyId) {
    CaseMetaModel metaModel = instance.getCaseMetaModel();
    if (metaModel == null) {
      return false;
    }

    CaseDefinition definition;
    try {
      definition = definitionRegistry.getCaseDefinition(metaModel);
    } catch (Exception e) {
      LOG.warnf(e, "Failed to resolve CaseDefinition for caseId=%s — skipping", instance.getUuid());
      return false;
    }
    if (definition == null) {
      return false;
    }

    List<LabelRule> rules = definition.getLabelRules();
    if (rules.isEmpty()) {
      return false;
    }

    Set<String> beforeLabels = new LinkedHashSet<>(instance.getLabels());

    Map<String, Object> context = Map.of();
    if (instance.getCaseContext() != null) {
      var workingNode = instance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode();
      if (workingNode != null) {
        context = MAPPER.convertValue(workingNode, MAP_TYPE);
      }
    }

    List<LabelAction> actions = LabelRule.evaluate(rules, context);
    Set<String> newLabels = new LinkedHashSet<>();
    for (LabelAction action : actions) {
      switch (action) {
        case LabelAction.Add add -> newLabels.add(add.label());
        case LabelAction.Remove remove -> newLabels.remove(remove.label());
      }
    }
    instance.setLabels(newLabels);

    if (!beforeLabels.equals(newLabels)) {
      caseInstanceRepository.update(instance, tenancyId);

      List<SubjectViewEvent> viewEvents =
          views.evaluateAndTrack(instance.getUuid(), tenancyId, newLabels);
      for (SubjectViewEvent ve : viewEvents) {
        queueEventConsumer.accept(
            new CaseQueueEvent(
                ve.subjectId(),
                ve.viewId(),
                ve.viewName(),
                CaseQueueEventType.from(ve.type()),
                ve.tenancyId()));
      }
      return true;
    }

    views.evaluateAndTrack(instance.getUuid(), tenancyId, newLabels);
    return false;
  }
}
