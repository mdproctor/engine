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
package io.casehub.engine.scheduler.quartz;

import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Quartz job for conditional scheduled triggers.
 *
 * <p>When this job fires, it:
 *
 * <ol>
 *   <li>Loads the case instance
 *   <li>Verifies the case is still RUNNING
 *   <li>Retrieves the binding from {@link CaseDefinition} and evaluates the condition from {@link
 *       Binding#getWhen()}
 *   <li>If condition is true, publishes a {@link WorkerScheduleEvent}
 *   <li>If condition is false, skips worker execution
 * </ol>
 *
 * <p>This job is used for time-based triggers with conditions (e.g., "escalate if order not
 * processed within 2 hours"). The condition is retrieved from the original {@link Binding}
 * definition, supporting any {@link ExpressionEvaluator} type including Java lambdas.
 */
@DisallowConcurrentExecution
@ApplicationScoped
public class ConditionalScheduledTriggerJob implements Job {

  private static final Logger LOG = Logger.getLogger(ConditionalScheduledTriggerJob.class);

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;

  @Inject ExpressionEngineRegistry expressionEngineRegistry;

  @Inject EventBus eventBus;

  @Inject WorkerExecutionRecoveryService recoveryService;

  @Inject io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry scopedWorkerRegistry;

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    JobDataMap data = context.getJobDetail().getJobDataMap();

    String caseIdStr = data.getString("caseId");
    String bindingName = data.getString("bindingName");
    String capabilityName = data.getString("capabilityName");
    String workerName = data.getString("workerName");

    LOG.infof(
        "Executing conditional scheduled trigger: caseId=%s, binding=%s, capability=%s",
        caseIdStr, bindingName, capabilityName);

    UUID caseId;
    try {
      caseId = UUID.fromString(caseIdStr);
    } catch (IllegalArgumentException e) {
      throw new JobExecutionException("Invalid caseId format: " + caseIdStr, e);
    }

    CaseInstance caseInstance;
    try {
      caseInstance = recoveryService.loadOrRestoreCaseInstance(caseId);
    } catch (Exception e) {
      LOG.warnf(e, "Failed to load case instance: %s, skipping scheduled trigger", caseId);
      return;
    }

    if (caseInstance.getState() != CaseStatus.RUNNING) {
      LOG.infof(
          "Case %s is %s (not RUNNING), skipping scheduled trigger",
          caseId, caseInstance.getState());
      return;
    }

    // Load CaseDefinition to get the Binding
    CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());

    if (definition == null) {
      throw new JobExecutionException(
          "CaseDefinition not found for case: " + caseInstance.getUuid());
    }

    // Find Binding by name and get the original condition
    Binding binding =
        definition.getBindings().stream()
            .filter(b -> b.getName().equals(bindingName))
            .findFirst()
            .orElseThrow(() -> new JobExecutionException("Binding not found: " + bindingName));

    ExpressionEvaluator condition = binding.getWhen();
    if (condition == null) {
      throw new JobExecutionException(
          "Binding '" + bindingName + "' has no condition (when clause)");
    }

    // Evaluate condition
    boolean conditionMet =
        expressionEngineRegistry.evaluate(condition, caseInstance.getCaseContext());

    if (!conditionMet) {
      LOG.infof(
          "Condition not met for binding=%s, case=%s, skipping worker execution",
          bindingName, caseId);
      return;
    }

    LOG.infof(
        "Condition met for binding=%s, case=%s, proceeding with worker execution",
        bindingName, caseId);

    Worker worker = findWorker(definition, workerName);
    if (worker == null) {
      throw new JobExecutionException("Worker not found: " + workerName);
    }

    Capability capability = findCapability(definition, capabilityName);
    if (capability == null) {
      throw new JobExecutionException("Capability not found: " + capabilityName);
    }

    if (binding.lifecycleScope() != io.casehub.api.model.LifecycleScope.BINDING) {
      var existing = scopedWorkerRegistry.get(caseId, bindingName);
      if (existing.isPresent()) {
        switch (existing.get()) {
          case io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession.Persistent p -> {
            com.fasterxml.jackson.databind.JsonNode snapshot =
                caseInstance
                    .getCaseContext()
                    .layer(io.casehub.api.context.ContextLayer.WORKING)
                    .asJsonNode();
            p.mailbox()
                .offer(
                    new io.casehub.engine.common.internal.worker.scope.ContextEvent(
                        snapshot, java.util.Map.of()));
          }
          case io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession.Reinvoked r -> {
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
                    io.casehub.api.model.event.ExecutionOrigin.SCHEDULE_TRIGGER,
                    List.of(),
                    binding.lifecycleScope(),
                    binding.executionMode(),
                    null,
                    null));
          }
        }
        return;
      }
    }

    LOG.infof(
        "Publishing WorkerScheduleEvent for case=%s, worker=%s, capability=%s",
        caseId, workerName, capabilityName);

    io.casehub.api.model.LifecycleScope ls = binding.lifecycleScope();
    io.casehub.api.model.ExecutionMode em = binding.executionMode();
    eventBus.publish(
        EventBusAddresses.WORKER_SCHEDULE,
        new WorkerScheduleEvent(
            caseInstance,
            worker,
            capability,
            bindingName,
            null,
            null,
            io.casehub.api.model.event.ExecutionOrigin.SCHEDULE_TRIGGER,
            List.of(),
            ls != io.casehub.api.model.LifecycleScope.BINDING ? ls : null,
            em != io.casehub.api.model.ExecutionMode.TRANSIENT ? em : null,
            null,
            null));
  }

  private Worker findWorker(CaseDefinition definition, String workerName) {
    return definition.getWorkers().stream()
        .filter(w -> w.name().equals(workerName))
        .findFirst()
        .orElse(null);
  }

  private Capability findCapability(CaseDefinition definition, String capabilityName) {
    return definition.getCapabilities().stream()
        .filter(c -> c.name().equals(capabilityName))
        .findFirst()
        .orElse(null);
  }
}
