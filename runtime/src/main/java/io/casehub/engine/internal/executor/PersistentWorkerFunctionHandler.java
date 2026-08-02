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
package io.casehub.engine.internal.executor;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.WorkerContext;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.WorkerFunctionHandler;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.engine.internal.worker.scope.DefaultPersistentScope;
import io.casehub.worker.api.PersistentScope;
import io.casehub.worker.api.ScopeTerminatedException;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.virtual.threads.VirtualThreads;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PersistentWorkerFunctionHandler implements WorkerFunctionHandler {

  private static final Logger LOG = Logger.getLogger(PersistentWorkerFunctionHandler.class);

  @Inject @VirtualThreads ExecutorService virtualThreads;
  @Inject ScopedWorkerRegistry scopedWorkerRegistry;
  @Inject WorkerRuntimeFactory workerRuntimeFactory;
  @Inject CaseDefinitionRegistry definitionRegistry;
  @Inject JQEvaluator jqEvaluator;
  @Inject EventBus eventBus;
  @Inject WorkerExecutionRecoveryService recoveryService;

  @Override
  public boolean supports(WorkerFunction<?, ?> function) {
    return function instanceof WorkerFunction.Persistent;
  }

  @SuppressWarnings("unchecked")
  @Override
  public WorkerResult<?> execute(
      WorkerFunction<?, ?> function,
      Object inputData,
      WorkerContext context,
      int timeoutMs,
      ExecutionMetadata metadata) {

    var persistent = (WorkerFunction.Persistent<?>) function;

    var session =
        scopedWorkerRegistry
            .get(context.caseId(), metadata.bindingName())
            .filter(ScopedWorkerSession.Persistent.class::isInstance)
            .map(ScopedWorkerSession.Persistent.class::cast)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Pre-registered persistent session not found for binding: "
                            + metadata.bindingName()));

    CaseInstance instance = recoveryService.loadOrRestoreCaseInstance(context.caseId());

    CaseDefinition definition = definitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    Binding binding = null;
    String inputProjection = null;
    String outputSchema = null;
    if (definition != null && definition.getBindings() != null) {
      binding =
          definition.getBindings().stream()
              .filter(b -> b.getName().equals(metadata.bindingName()))
              .findFirst()
              .orElse(null);
      if (binding != null && binding.target() instanceof CapabilityTarget ct) {
        inputProjection = binding.effectiveInputProjection(ct.capability());
        outputSchema = ct.capability().outputSchema();
      }
    }

    var scope =
        new DefaultPersistentScope<>(
            persistent.inputType(),
            session.mailbox(),
            context.caseId(),
            metadata.workerName(),
            context,
            eventBus,
            inputProjection,
            outputSchema,
            jqEvaluator,
            workerRuntimeFactory,
            instance,
            metadata.bindingName());

    String bindingName = metadata.bindingName();
    virtualThreads.submit(
        () -> {
          try {
            ((Consumer<PersistentScope<?>>) (Consumer) persistent.handler()).accept(scope);
            publishCompletion(context, metadata, WorkerOutcome.completed(), bindingName);
          } catch (ScopeTerminatedException e) {
            LOG.debugf(
                "Persistent worker '%s' terminated by engine for binding '%s'",
                metadata.workerName(), bindingName);
          } catch (Exception e) {
            LOG.errorf(
                e,
                "Persistent worker '%s' faulted for binding '%s'",
                metadata.workerName(),
                bindingName);
            publishCompletion(
                context, metadata, new WorkerOutcome.Failed<>(e.getMessage()), bindingName);
          }
        });

    return WorkerResult.of(Map.of());
  }

  private void publishCompletion(
      WorkerContext context,
      ExecutionMetadata metadata,
      WorkerOutcome<?> outcome,
      String bindingName) {
    CaseInstance freshInstance = recoveryService.loadOrRestoreCaseInstance(context.caseId());
    if (freshInstance == null) {
      LOG.warnf(
          "Case %s evicted before persistent worker '%s' completion — skipping",
          context.caseId(), metadata.workerName());
      return;
    }

    CaseDefinition definition =
        definitionRegistry.getCaseDefinition(freshInstance.getCaseMetaModel());
    Worker worker = null;
    if (definition != null) {
      worker =
          definition.getWorkers().stream()
              .filter(w -> w.name().equals(metadata.workerName()))
              .findFirst()
              .orElse(null);
    }

    eventBus.publish(
        EventBusAddresses.WORKER_EXECUTION_FINISHED,
        new WorkflowExecutionCompleted(
            freshInstance, worker, metadata.inputDataHash(), Map.of(), bindingName, outcome));
  }
}
