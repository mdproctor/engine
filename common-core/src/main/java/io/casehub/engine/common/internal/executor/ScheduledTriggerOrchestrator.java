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
package io.casehub.engine.common.internal.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ExecutionMode;
import io.casehub.api.model.LifecycleScope;
import io.casehub.api.model.event.ExecutionOrigin;
import io.casehub.engine.common.internal.event.ContextSignalEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.worker.scope.ContextEvent;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Scheduler-agnostic orchestrator for scheduled trigger execution. Extracts all domain logic from
 * ScheduledTriggerJob, ConditionalScheduledTriggerJob, and ScheduledSignalJob into a single
 * reusable bean that any scheduler backend can delegate to.
 */
@ApplicationScoped
public class ScheduledTriggerOrchestrator {

  private static final Logger LOG = Logger.getLogger(ScheduledTriggerOrchestrator.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final WorkerExecutionRecoveryService recoveryService;
  private final ScopedWorkerRegistry scopedWorkerRegistry;
  private final ExpressionEngineRegistry expressionEngineRegistry;
  private final EventBus eventBus;

  @Inject
  public ScheduledTriggerOrchestrator(
      CaseDefinitionRegistry caseDefinitionRegistry,
      WorkerExecutionRecoveryService recoveryService,
      ScopedWorkerRegistry scopedWorkerRegistry,
      ExpressionEngineRegistry expressionEngineRegistry,
      EventBus eventBus) {
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.recoveryService = recoveryService;
    this.scopedWorkerRegistry = scopedWorkerRegistry;
    this.expressionEngineRegistry = expressionEngineRegistry;
    this.eventBus = eventBus;
  }

  public void executeUnconditionalTrigger(ScheduledTriggerData data) {
    LOG.infof(
        "Executing scheduled trigger: caseId=%s, binding=%s, capability=%s",
        data.caseId(), data.bindingName(), data.capabilityName());
    executeWorkerTrigger(data, false);
  }

  public void executeConditionalTrigger(ScheduledTriggerData data) {
    LOG.infof(
        "Executing conditional scheduled trigger: caseId=%s, binding=%s, capability=%s",
        data.caseId(), data.bindingName(), data.capabilityName());
    executeWorkerTrigger(data, true);
  }

  public void executeSignalTrigger(ScheduledSignalData data) {
    UUID caseId = data.caseId();
    LOG.infof("Signal trigger fired: case=%s, binding=%s", caseId, data.bindingName());

    CaseInstance caseInstance = loadRunningCase(caseId);
    if (caseInstance == null) {
      return;
    }

    if (data.hasCondition()) {
      CaseDefinition definition =
          caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());
      if (definition == null) {
        LOG.warnf("CaseDefinition not found for case %s", caseId);
        return;
      }
      Binding binding = findBinding(definition, data.bindingName());
      if (binding == null || binding.getWhen() == null) {
        LOG.warnf("Binding '%s' not found or has no condition", data.bindingName());
        return;
      }
      try {
        boolean conditionMet =
            expressionEngineRegistry.evaluate(binding.getWhen(), caseInstance.getCaseContext());
        if (!conditionMet) {
          LOG.debugf("Signal condition not met for case=%s binding=%s", caseId, data.bindingName());
          return;
        }
      } catch (Exception e) {
        LOG.warnf(
            e, "Condition evaluation failed for case=%s binding=%s", caseId, data.bindingName());
        return;
      }
    }

    try {
      Map<String, Object> payload =
          OBJECT_MAPPER.readValue(data.signalPayload(), new TypeReference<>() {});
      eventBus.publish(
          EventBusAddresses.CONTEXT_SIGNAL,
          new ContextSignalEvent(caseInstance, data.bindingName(), payload));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse signal payload", e);
    }
  }

  private void executeWorkerTrigger(ScheduledTriggerData data, boolean evaluateCondition) {
    UUID caseId = data.caseId();

    CaseInstance caseInstance = loadRunningCase(caseId);
    if (caseInstance == null) {
      return;
    }

    CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());
    if (definition == null) {
      throw new IllegalStateException("CaseDefinition not found for case: " + caseId);
    }

    Binding binding = findBinding(definition, data.bindingName());

    if (evaluateCondition) {
      if (binding == null) {
        throw new IllegalStateException("Binding not found: " + data.bindingName());
      }
      if (binding.getWhen() == null) {
        throw new IllegalStateException(
            "Binding '" + data.bindingName() + "' has no condition (when clause)");
      }
      boolean conditionMet =
          expressionEngineRegistry.evaluate(binding.getWhen(), caseInstance.getCaseContext());
      if (!conditionMet) {
        LOG.infof(
            "Condition not met for binding=%s, case=%s, skipping worker execution",
            data.bindingName(), caseId);
        return;
      }
      LOG.infof(
          "Condition met for binding=%s, case=%s, proceeding with worker execution",
          data.bindingName(), caseId);
    }

    Worker worker = findWorker(definition, data.workerName());
    if (worker == null) {
      throw new IllegalStateException("Worker not found: " + data.workerName());
    }

    Capability capability = findCapability(definition, data.capabilityName());
    if (capability == null) {
      throw new IllegalStateException("Capability not found: " + data.capabilityName());
    }

    LifecycleScope ls = binding != null ? binding.lifecycleScope() : null;
    ExecutionMode em = binding != null ? binding.executionMode() : null;

    if (binding != null && ls != LifecycleScope.BINDING) {
      var existing = scopedWorkerRegistry.get(caseId, data.bindingName());
      if (existing.isPresent()) {
        handleScopedWorker(
            existing.get(), caseInstance, definition, capability, data.bindingName(), ls, em);
        return;
      }
    }

    LOG.infof(
        "Publishing WorkerScheduleEvent for case=%s, worker=%s, capability=%s",
        caseId, data.workerName(), data.capabilityName());

    eventBus.publish(
        EventBusAddresses.WORKER_SCHEDULE,
        new WorkerScheduleEvent(
            caseInstance,
            worker,
            capability,
            data.bindingName(),
            null,
            null,
            ExecutionOrigin.SCHEDULE_TRIGGER,
            List.of(),
            ls != LifecycleScope.BINDING ? ls : null,
            em != ExecutionMode.TRANSIENT ? em : null,
            null,
            null,
            null));
  }

  private void handleScopedWorker(
      ScopedWorkerSession session,
      CaseInstance caseInstance,
      CaseDefinition definition,
      Capability capability,
      String bindingName,
      LifecycleScope ls,
      ExecutionMode em) {
    switch (session) {
      case ScopedWorkerSession.Persistent p -> {
        JsonNode snapshot = caseInstance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode();
        p.mailbox().offer(new ContextEvent(snapshot, Map.of()));
      }
      case ScopedWorkerSession.Reinvoked r -> {
        Worker sessionWorker = findWorker(definition, r.executorName());
        if (sessionWorker == null) {
          LOG.warnf(
              "Executor '%s' no longer in definition for binding '%s'",
              r.executorName(), bindingName);
          return;
        }
        eventBus.publish(
            EventBusAddresses.WORKER_SCHEDULE,
            new WorkerScheduleEvent(
                caseInstance,
                sessionWorker,
                capability,
                bindingName,
                null,
                null,
                ExecutionOrigin.SCHEDULE_TRIGGER,
                List.of(),
                ls,
                em,
                null,
                null,
                null));
      }
    }
  }

  private CaseInstance loadRunningCase(UUID caseId) {
    CaseInstance caseInstance;
    try {
      caseInstance = recoveryService.loadOrRestoreCaseInstance(caseId);
    } catch (Exception e) {
      LOG.warnf(e, "Failed to load case instance: %s, skipping scheduled trigger", caseId);
      return null;
    }
    if (caseInstance.getState() != CaseStatus.RUNNING) {
      LOG.infof(
          "Case %s is %s (not RUNNING), skipping scheduled trigger",
          caseId, caseInstance.getState());
      return null;
    }
    return caseInstance;
  }

  private static Binding findBinding(CaseDefinition definition, String bindingName) {
    if (definition.getBindings() == null) {
      return null;
    }
    return definition.getBindings().stream()
        .filter(b -> b.getName().equals(bindingName))
        .findFirst()
        .orElse(null);
  }

  private static Worker findWorker(CaseDefinition definition, String workerName) {
    return definition.getWorkers().stream()
        .filter(w -> w.name().equals(workerName))
        .findFirst()
        .orElse(null);
  }

  private static Capability findCapability(CaseDefinition definition, String capabilityName) {
    return definition.getCapabilities().stream()
        .filter(c -> c.name().equals(capabilityName))
        .findFirst()
        .orElse(null);
  }
}
