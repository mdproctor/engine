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
package io.casehub.engine.internal.worker.scope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.ExecutionMode;
import io.casehub.api.model.WorkerContext;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.ScopedWorkerOutputEvent;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.internal.executor.WorkerRuntimeFactory;
import io.casehub.worker.api.PersistentScope;
import io.casehub.worker.api.ScopeTerminatedException;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

public class DefaultPersistentScope<T> implements PersistentScope<T> {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final Class<T> inputType;
  private final BlockingQueue<io.casehub.engine.common.internal.worker.scope.ContextEvent> mailbox;
  private final UUID caseId;
  private final String taskId;
  private final EventBus eventBus;
  private final String inputProjection;
  private final String outputSchema;
  private final JQEvaluator jqEvaluator;
  private final io.casehub.api.engine.WorkerRuntime innerRuntime;
  private final CaseInstance caseInstance;

  public DefaultPersistentScope(
      Class<T> inputType,
      BlockingQueue<io.casehub.engine.common.internal.worker.scope.ContextEvent> mailbox,
      UUID caseId,
      String taskId,
      WorkerContext context,
      EventBus eventBus,
      String inputProjection,
      String outputSchema,
      JQEvaluator jqEvaluator,
      WorkerRuntimeFactory workerRuntimeFactory,
      CaseInstance caseInstance) {
    this.inputType = inputType;
    this.mailbox = mailbox;
    this.caseId = caseId;
    this.taskId = taskId;
    this.eventBus = eventBus;
    this.inputProjection = inputProjection;
    this.outputSchema = outputSchema;
    this.jqEvaluator = jqEvaluator;
    this.innerRuntime = workerRuntimeFactory.create(caseId, taskId, context);
    this.caseInstance = caseInstance;
  }

  @Override
  public T nextEvent() throws ScopeTerminatedException {
    try {
      io.casehub.engine.common.internal.worker.scope.ContextEvent event = mailbox.take();
      if (event.isShutdown()) {
        throw new ScopeTerminatedException();
      }
      JsonNode snapshot = event.contextSnapshot();
      if (inputProjection != null) {
        ValidationResult result = jqEvaluator.eval(inputProjection, snapshot);
        if (result.ok() && result.output() != null && !result.output().isEmpty()) {
          snapshot = result.output().get(0);
        }
      }
      return MAPPER.convertValue(snapshot, inputType);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScopeTerminatedException();
    }
  }

  @Override
  public void emit(Map<String, Object> output) {
    Map<String, Object> projected = output;
    if (outputSchema != null && output != null && !output.isEmpty()) {
      try {
        JsonNode outputNode = MAPPER.valueToTree(output);
        ValidationResult result = jqEvaluator.eval(outputSchema, outputNode);
        if (result.ok() && result.output() != null && !result.output().isEmpty()) {
          projected = MAPPER.convertValue(result.output().get(0), MAP_TYPE);
        }
      } catch (Exception e) {
        // fall through with unprojected output
      }
    }
    eventBus.publish(
        EventBusAddresses.SCOPED_WORKER_OUTPUT,
        new ScopedWorkerOutputEvent(caseInstance, taskId, projected, ExecutionMode.PERSISTENT));
  }

  @Override
  public UUID caseId() {
    return caseId;
  }

  @Override
  public String taskId() {
    return taskId;
  }

  @Override
  public <I, R> WorkerResult<R> execute(WorkerFunction<I, R> function, I input) {
    return innerRuntime.execute(function, input);
  }

  @Override
  public WorkerResult<?> execute(String workerName, Map<String, Object> input) {
    return innerRuntime.execute(workerName, input);
  }

  @Override
  public Map<String, Object> accumulatedState() {
    return Map.of();
  }
}
