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
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.WorkerContext;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.ScopedWorkerOutputEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.internal.executor.WorkerRuntimeFactory;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.worker.api.PersistentScope;
import io.casehub.worker.api.ScopeTerminatedException;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

public class DefaultPersistentScope<T> implements PersistentScope<T> {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final Class<T> inputType;
  private final BlockingQueue<io.casehub.engine.common.internal.worker.scope.ContextEvent> mailbox;
  private final UUID caseId;
  private final String taskId;
  private final EventBus eventBus;
  private final ExpressionEvaluator inputProjection;
  private final ExpressionEvaluator outputProjection;
  private final ExpressionEngineRegistry expressionEngineRegistry;
  private final io.casehub.api.engine.WorkerRuntime innerRuntime;
  private final CaseInstance caseInstance;
  private final String bindingName;
  private final AtomicReference<String> reasoning = new AtomicReference<>();

  public DefaultPersistentScope(
      Class<T> inputType,
      BlockingQueue<io.casehub.engine.common.internal.worker.scope.ContextEvent> mailbox,
      UUID caseId,
      String taskId,
      WorkerContext context,
      EventBus eventBus,
      ExpressionEvaluator inputProjection,
      ExpressionEvaluator outputProjection,
      ExpressionEngineRegistry expressionEngineRegistry,
      WorkerRuntimeFactory workerRuntimeFactory,
      CaseInstance caseInstance,
      String bindingName) {
    this.inputType = inputType;
    this.mailbox = mailbox;
    this.caseId = caseId;
    this.taskId = taskId;
    this.eventBus = eventBus;
    this.inputProjection = inputProjection;
    this.outputProjection = outputProjection;
    this.expressionEngineRegistry = expressionEngineRegistry;
    this.innerRuntime = workerRuntimeFactory.create(caseId, taskId, context);
    this.caseInstance = caseInstance;
    this.bindingName = bindingName;
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
        List<JsonNode> result = expressionEngineRegistry.transform(inputProjection, snapshot);
        if (!result.isEmpty()) {
          snapshot = result.get(0);
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
    if (outputProjection != null && output != null && !output.isEmpty()) {
      try {
        JsonNode outputNode = MAPPER.valueToTree(output);
        List<JsonNode> result = expressionEngineRegistry.transform(outputProjection, outputNode);
        if (!result.isEmpty()) {
          projected = MAPPER.convertValue(result.get(0), MAP_TYPE);
        }
      } catch (Exception e) {
        // fall through with unprojected output
      }
    }
    eventBus.publish(
        EventBusAddresses.SCOPED_WORKER_OUTPUT,
        new ScopedWorkerOutputEvent(caseInstance, taskId, projected, bindingName, null));
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

  public void reasoning(String reasoning) {
    this.reasoning.set(reasoning);
  }

  public String getReasoning() {
    return reasoning.get();
  }
}
