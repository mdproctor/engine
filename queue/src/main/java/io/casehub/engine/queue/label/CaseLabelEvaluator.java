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
package io.casehub.engine.queue.label;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.queue.event.CaseQueueEvent;
import io.casehub.engine.queue.event.CaseQueueEventType;
import io.casehub.platform.api.label.LabelAction;
import io.casehub.platform.api.label.LabelRule;
import io.casehub.platform.api.view.SubjectViewEvent;
import io.casehub.platform.view.SubjectViewOrchestrator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CaseLabelEvaluator {

  private static final Logger LOG = Logger.getLogger(CaseLabelEvaluator.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "FAULTED", "CANCELLED");

  @Inject CaseDefinitionRegistry definitionRegistry;

  @Inject CaseInstanceRepository caseInstanceRepository;

  @Inject SubjectViewOrchestrator views;

  @Inject Event<CaseQueueEvent> queueEvents;

  private final ConcurrentHashMap<UUID, ReentrantLock> caseLocks = new ConcurrentHashMap<>();

  public void onCaseLifecycle(@ObservesAsync CaseLifecycleEvent event) {
    UUID caseId = event.caseId();
    String tenancyId = event.tenancyId();
    boolean isTerminal =
        event.caseStatus() != null && TERMINAL_STATUSES.contains(event.caseStatus());

    ReentrantLock lock = caseLocks.computeIfAbsent(caseId, k -> new ReentrantLock());
    lock.lock();
    try {
      CaseInstance instance = caseInstanceRepository.findByUuid(caseId, tenancyId);
      if (instance == null) {
        LOG.debugf(
            "CaseInstance not found for caseId=%s tenancyId=%s — skipping label evaluation",
            caseId, tenancyId);
        return;
      }

      CaseMetaModel metaModel = instance.getCaseMetaModel();
      if (metaModel == null) {
        return;
      }

      CaseDefinition definition = definitionRegistry.getCaseDefinition(metaModel);
      if (definition == null) {
        return;
      }

      List<LabelRule> rules = definition.getLabelRules();
      if (rules.isEmpty() && !isTerminal) {
        return;
      }

      Set<String> beforeLabels = new LinkedHashSet<>(instance.getLabels());

      if (isTerminal) {
        instance.getLabels().clear();
      } else {
        JsonNode contextSnapshot = event.contextSnapshot();
        Map<String, Object> context =
            contextSnapshot != null ? MAPPER.convertValue(contextSnapshot, MAP_TYPE) : Map.of();

        List<LabelAction> actions = LabelRule.evaluate(rules, context);
        Set<String> newLabels = new LinkedHashSet<>();
        for (LabelAction action : actions) {
          switch (action) {
            case LabelAction.Add add -> newLabels.add(add.label());
            case LabelAction.Remove remove -> newLabels.remove(remove.label());
          }
        }
        instance.setLabels(newLabels);
      }

      Set<String> afterLabels = instance.getLabels();

      if (!beforeLabels.equals(afterLabels)) {
        caseInstanceRepository.update(instance, tenancyId);

        List<SubjectViewEvent> viewEvents = views.evaluateAndTrack(caseId, tenancyId, afterLabels);
        for (SubjectViewEvent ve : viewEvents) {
          queueEvents.fire(
              new CaseQueueEvent(
                  ve.subjectId(),
                  ve.viewId(),
                  ve.viewName(),
                  CaseQueueEventType.from(ve.type()),
                  ve.tenancyId()));
        }
      }

      if (isTerminal) {
        caseLocks.remove(caseId);
      }
    } finally {
      lock.unlock();
    }
  }
}
