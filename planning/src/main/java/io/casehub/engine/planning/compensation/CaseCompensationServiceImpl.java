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
package io.casehub.engine.planning.compensation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.engine.CaseCompensationService;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.routing.BindingExecutorResolver;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.JudgmentScheduleRequest;
import io.casehub.engine.common.spi.JudgmentScheduler;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.worker.api.Worker;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CaseCompensationServiceImpl implements CaseCompensationService {

  private static final Logger LOG = Logger.getLogger(CaseCompensationServiceImpl.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final CaseInstanceCache caseInstanceCache;
  private final EventBus eventBus;
  private final BlackboardRegistry blackboardRegistry;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final EventLogRepository eventLogRepository;
  private final Instance<JudgmentScheduler> judgmentScheduler;

  @Inject
  public CaseCompensationServiceImpl(
      CaseInstanceCache caseInstanceCache,
      EventBus eventBus,
      BlackboardRegistry blackboardRegistry,
      CaseDefinitionRegistry caseDefinitionRegistry,
      EventLogRepository eventLogRepository,
      Instance<JudgmentScheduler> judgmentScheduler) {
    this.caseInstanceCache = caseInstanceCache;
    this.eventBus = eventBus;
    this.blackboardRegistry = blackboardRegistry;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.eventLogRepository = eventLogRepository;
    this.judgmentScheduler = judgmentScheduler;
  }

  @Override
  public void compensate(UUID caseId, String triggeredBy, String reason) {
    CaseInstance instance = caseInstanceCache.get(caseId);
    if (instance == null) {
      throw new IllegalArgumentException("Case not found: " + caseId);
    }

    CaseStatus current = instance.getState();
    if (current != CaseStatus.COMPLETED && current != CaseStatus.COMPENSATION_FAULTED) {
      throw new IllegalStateException(
          "Cannot compensate case in state "
              + current
              + ": caseId="
              + caseId
              + ". Valid entry points: COMPLETED, COMPENSATION_FAULTED");
    }

    LOG.infof("Compensation triggered for caseId=%s by %s: %s", caseId, triggeredBy, reason);

    eventBus.publish(
        EventBusAddresses.CASE_STATUS_CHANGED,
        new CaseStatusChanged(instance, current.name(), CaseStatus.COMPENSATING.name()));

    appendCompensationEvent(instance, CaseHubEventType.COMPENSATION_STARTED, triggeredBy, reason);

    fireNextCompensationStep(instance);
  }

  void fireNextCompensationStep(CaseInstance instance) {
    UUID caseId = instance.getUuid();
    CaseDefinition definition = resolveDefinition(instance);
    if (definition == null) {
      LOG.errorf("No definition found for caseId=%s — cannot proceed with compensation", caseId);
      transitionToFaulted(instance, "No case definition found");
      return;
    }

    Map<String, Binding> bindingsByName = new LinkedHashMap<>();
    for (Binding b : definition.getBindings()) {
      bindingsByName.put(b.getName(), b);
    }

    CasePlanModel plan = blackboardRegistry.getOrCreate(caseId, instance.tenancyId);
    List<PlanItem> completedItems =
        plan.getAllPlanItems().stream()
            .filter(pi -> pi.getStatus() == TaskStatus.COMPLETED)
            .filter(pi -> !pi.isCompensation())
            .filter(
                pi -> {
                  Binding b = bindingsByName.get(pi.getBindingName());
                  return b != null && b.getCompensateRef() != null;
                })
            .toList();

    if (completedItems.isEmpty()) {
      LOG.infof("No compensable PlanItems for caseId=%s — marking COMPENSATED", caseId);
      transitionToCompensated(instance);
      return;
    }

    Set<String> alreadyCompensated = new HashSet<>();
    for (PlanItem pi : plan.getAllPlanItems()) {
      if (pi.isCompensation()
          && pi.getStatus() == TaskStatus.COMPLETED
          && pi.getCompensatesItemId() != null) {
        alreadyCompensated.add(pi.getCompensatesItemId());
      }
    }

    List<PlanItem> remaining =
        completedItems.stream()
            .filter(pi -> !alreadyCompensated.contains(pi.getPlanItemId()))
            .toList();

    if (remaining.isEmpty()) {
      LOG.infof("All compensable PlanItems already compensated for caseId=%s", caseId);
      transitionToCompensated(instance);
      return;
    }

    List<PlanItem> ordered = buildReverseTopologicalOrder(remaining, bindingsByName);

    PlanItem next = ordered.get(0);
    Binding originalBinding = bindingsByName.get(next.getBindingName());
    String compensateRef = originalBinding.getCompensateRef();
    Binding compensatingBinding = bindingsByName.get(compensateRef);

    if (compensatingBinding == null) {
      LOG.errorf("Compensating binding '%s' not found for caseId=%s", compensateRef, caseId);
      transitionToFaulted(instance, "Compensating binding '" + compensateRef + "' not found");
      return;
    }

    ExecutorRef executor = BindingExecutorResolver.resolve(compensatingBinding, definition);
    PlanItem compensatingItem =
        PlanItem.create(compensatingBinding.getName(), executor, 0, compensatingBinding.target());
    compensatingItem.setCompensation(true);
    compensatingItem.setCompensatesItemId(next.getPlanItemId());
    plan.addPlanItem(compensatingItem);

    appendStepEvent(
        instance,
        CaseHubEventType.COMPENSATION_STEP_STARTED,
        next.getBindingName(),
        compensatingBinding.getName());

    LOG.infof(
        "Fired compensating binding '%s' for original '%s' (caseId=%s)",
        compensatingBinding.getName(), next.getBindingName(), caseId);

    dispatchCompensatingBinding(instance, definition, compensatingBinding, compensatingItem);
  }

  private void dispatchCompensatingBinding(
      CaseInstance instance,
      CaseDefinition definition,
      Binding compensatingBinding,
      PlanItem compensatingItem) {
    compensatingItem.tryMarkDispatching();

    switch (compensatingBinding.target()) {
      case CapabilityTarget ct -> {
        String executorName = compensatingItem.executorName();
        Worker worker =
            definition.getWorkers().stream()
                .filter(w -> w.name().equals(executorName))
                .findFirst()
                .orElse(null);
        if (worker == null) {
          LOG.errorf(
              "Worker '%s' not found for compensation dispatch, caseId=%s",
              executorName, instance.getUuid());
          transitionToFaulted(instance, "Worker '" + executorName + "' not found");
          return;
        }
        eventBus.publish(
            EventBusAddresses.WORKER_SCHEDULE,
            new WorkerScheduleEvent(
                instance, worker, ct.capability(), compensatingBinding.getName()));
      }
      case JudgmentTarget jt -> {
        if (!judgmentScheduler.isResolvable()) {
          LOG.errorf(
              "No JudgmentScheduler on classpath for compensation binding '%s', caseId=%s",
              compensatingBinding.getName(), instance.getUuid());
          transitionToFaulted(instance, "No JudgmentScheduler available");
          return;
        }
        judgmentScheduler
            .get()
            .schedule(
                new JudgmentScheduleRequest(
                    instance.getUuid(),
                    instance.tenancyId,
                    compensatingBinding.getName(),
                    jt,
                    Map.of(),
                    null,
                    null));
      }
      default -> {
        String targetType =
            compensatingBinding.target() != null
                ? compensatingBinding.target().getClass().getSimpleName()
                : "null";
        LOG.errorf(
            "Unsupported target type '%s' for compensation binding '%s', caseId=%s",
            targetType, compensatingBinding.getName(), instance.getUuid());
        transitionToFaulted(instance, "Unsupported compensation target type: " + targetType);
      }
    }
  }

  void onCompensationPlanItemStateChanged(@ObservesAsync PlanItemStateChangedEvent event) {
    if (event.newStatus() != TaskStatus.COMPLETED && event.newStatus() != TaskStatus.FAULTED) {
      return;
    }

    CasePlanModel plan = blackboardRegistry.get(event.caseId()).orElse(null);
    if (plan == null) {
      return;
    }

    PlanItem item = plan.getPlanItem(event.planItemId()).orElse(null);
    if (item == null || !item.isCompensation()) {
      return;
    }

    CaseInstance instance = caseInstanceCache.get(event.caseId());
    if (instance == null || instance.getState() != CaseStatus.COMPENSATING) {
      return;
    }

    if (event.newStatus() == TaskStatus.COMPLETED) {
      if (item.getCompensatesItemId() != null) {
        PlanItem originalItem = plan.getPlanItem(item.getCompensatesItemId()).orElse(null);
        String originalBindingName =
            originalItem != null ? originalItem.getBindingName() : "unknown";
        appendStepEvent(
            instance,
            CaseHubEventType.COMPENSATION_STEP_COMPLETED,
            originalBindingName,
            event.bindingName());
      }
      fireNextCompensationStep(instance);
    } else {
      transitionToFaulted(instance, "Compensating binding '" + event.bindingName() + "' faulted");
    }
  }

  List<PlanItem> buildReverseTopologicalOrder(
      List<PlanItem> items, Map<String, Binding> bindingsByName) {
    Map<String, PlanItem> byId = new LinkedHashMap<>();
    for (PlanItem pi : items) {
      byId.put(pi.getPlanItemId(), pi);
    }

    Map<String, Set<String>> dependsOn = new HashMap<>();
    for (PlanItem pi : items) {
      Binding b = bindingsByName.get(pi.getBindingName());
      if (b == null) continue;
      Set<String> deps = new HashSet<>();
      if (b.getConsumes() != null) {
        for (PlanItem other : items) {
          if (other == pi) continue;
          Binding ob = bindingsByName.get(other.getBindingName());
          if (ob != null && b.getConsumes().equals(ob.getProduces())) {
            deps.add(other.getPlanItemId());
          }
        }
      }
      dependsOn.put(pi.getPlanItemId(), deps);
    }

    List<PlanItem> sorted = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    for (PlanItem pi : items) {
      topoVisit(pi.getPlanItemId(), byId, dependsOn, visited, sorted);
    }
    java.util.Collections.reverse(sorted);
    return sorted;
  }

  private void topoVisit(
      String id,
      Map<String, PlanItem> byId,
      Map<String, Set<String>> dependsOn,
      Set<String> visited,
      List<PlanItem> sorted) {
    if (!visited.add(id)) return;
    for (String dep : dependsOn.getOrDefault(id, Set.of())) {
      topoVisit(dep, byId, dependsOn, visited, sorted);
    }
    PlanItem pi = byId.get(id);
    if (pi != null) sorted.add(pi);
  }

  private void transitionToCompensated(CaseInstance instance) {
    eventBus.publish(
        EventBusAddresses.CASE_STATUS_CHANGED,
        new CaseStatusChanged(
            instance, CaseStatus.COMPENSATING.name(), CaseStatus.COMPENSATED.name()));
    appendCompensationEvent(instance, CaseHubEventType.COMPENSATION_COMPLETED, null, null);
  }

  void transitionToFaulted(CaseInstance instance, String errorDetail) {
    eventBus.publish(
        EventBusAddresses.CASE_STATUS_CHANGED,
        new CaseStatusChanged(
            instance, CaseStatus.COMPENSATING.name(), CaseStatus.COMPENSATION_FAULTED.name()));
    appendCompensationEvent(instance, CaseHubEventType.COMPENSATION_FAULTED, null, errorDetail);
  }

  private void appendCompensationEvent(
      CaseInstance instance, CaseHubEventType type, String triggeredBy, String reason) {
    EventLog log = new EventLog();
    log.setCaseId(instance.getUuid());
    log.setEventType(type);
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
    if (triggeredBy != null) metadata.put("triggeredBy", triggeredBy);
    if (reason != null) metadata.put("reason", reason);
    log.setMetadata(metadata);
    eventLogRepository.append(log, instance.tenancyId);
  }

  private void appendStepEvent(
      CaseInstance instance,
      CaseHubEventType type,
      String originalBindingName,
      String compensatingBindingName) {
    EventLog log = new EventLog();
    log.setCaseId(instance.getUuid());
    log.setEventType(type);
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
    metadata.put("originalBinding", originalBindingName);
    metadata.put("compensatingBinding", compensatingBindingName);
    log.setMetadata(metadata);
    eventLogRepository.append(log, instance.tenancyId);
  }

  private CaseDefinition resolveDefinition(CaseInstance instance) {
    if (instance.getCaseMetaModel() == null) return null;
    return caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
  }
}
