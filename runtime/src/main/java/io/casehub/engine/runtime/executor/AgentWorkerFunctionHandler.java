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
package io.casehub.engine.runtime.executor;

import io.casehub.api.model.AgentWorkerFunction;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.ai.DeferredAgentConfig;
import io.casehub.api.model.ai.WorkerAgentInvoker;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.SystemPromptRenderer;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.HandlerResult;
import io.casehub.engine.common.internal.executor.WorkerFunctionHandler;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Handler for {@link AgentWorkerFunction}. Executes agent invocations on a virtual thread with
 * timeout, capturing protocol metadata (tokens, cost, duration) from the invocation result.
 *
 * <p>Supports two modes: eager (pre-built invoker) and deferred (slot-based resolution via {@link
 * AgentRegistry} at execution time).
 *
 * <p>Does NOT delegate to the worker executor — {@link AgentWorkerFunction} has no {@link
 * WorkerFunction.Sync} fn() to lift.
 *
 * <p>Complementary to other handlers — this is {@code @ApplicationScoped} (not
 * {@code @DefaultBean}).
 *
 * <p>Refs casehubio/engine#726, casehubio/engine#1333.
 */
@ApplicationScoped
public class AgentWorkerFunctionHandler implements WorkerFunctionHandler {

  private final ExecutorService virtualThreads;
  private final AgentRegistry agentRegistry;
  private final SystemPromptRenderer systemPromptRenderer;
  private final AgentProvider agentProvider;

  @Inject
  public AgentWorkerFunctionHandler(
      @VirtualThreads ExecutorService virtualThreads,
      AgentRegistry agentRegistry,
      SystemPromptRenderer systemPromptRenderer,
      AgentProvider agentProvider) {
    this.virtualThreads = virtualThreads;
    this.agentRegistry = agentRegistry;
    this.systemPromptRenderer = systemPromptRenderer;
    this.agentProvider = agentProvider;
  }

  public AgentWorkerFunctionHandler(ExecutorService virtualThreads) {
    this(virtualThreads, null, null, null);
  }

  @Override
  public boolean supports(WorkerFunction<?, ?> function) {
    return function instanceof AgentWorkerFunction;
  }

  @SuppressWarnings("unchecked")
  @Override
  public HandlerResult execute(
      Worker worker,
      Capability capability,
      Object inputData,
      WorkerContext context,
      ExecutionMetadata metadata) {

    AgentWorkerFunction agent = (AgentWorkerFunction) worker.function();

    if (agent.isDeferred()) {
      return switch (agent.deferredConfig()) {
        case DeferredAgentConfig.SlotConfig sc -> executeBySlot(sc, worker, inputData, metadata);
        case DeferredAgentConfig.CapabilityConfig cc ->
            throw new UnsupportedOperationException("Capability dispatch not yet implemented");
      };
    }
    return executeWithInvoker(agent.invoker(), worker, inputData);
  }

  private HandlerResult executeBySlot(
      DeferredAgentConfig.SlotConfig slotConfig,
      Worker worker,
      Object inputData,
      ExecutionMetadata metadata) {

    String tenancyId = metadata.tenancyId();
    java.util.List<io.casehub.eidos.api.AgentMatch> matches =
        agentRegistry.find(io.casehub.eidos.api.AgentQuery.bySlot(slotConfig.slot(), tenancyId));
    if (matches.isEmpty()) {
      return new HandlerResult(
          WorkerResult.failed("Agent not found for slot: " + slotConfig.slot()));
    }

    AgentDescriptor descriptor = matches.get(0).descriptor();
    SystemPromptRenderer.RenderedPrompt rendered =
        systemPromptRenderer.render(
            descriptor, AgentPromptContext.forFormat(SystemPromptRenderer.RenderFormat.MARKDOWN));

    String model = slotConfig.model();
    String userMessageTemplate = slotConfig.userMessageTemplate();
    UnaryOperator<com.fasterxml.jackson.databind.JsonNode> inputTransformer =
        slotConfig.inputProjection() != null
            ? new io.casehub.api.model.ai.JqTransformer(slotConfig.inputProjection())::apply
            : UnaryOperator.identity();
    UnaryOperator<com.fasterxml.jackson.databind.JsonNode> outputTransformer =
        slotConfig.outputProjection() != null
            ? new io.casehub.api.model.ai.JqTransformer(slotConfig.outputProjection())::apply
            : UnaryOperator.identity();

    WorkerAgentInvoker invoker =
        new WorkerAgentInvoker(
            agentProvider,
            rendered.content(),
            userMessageTemplate,
            inputTransformer,
            outputTransformer,
            null,
            model,
            null);

    return executeWithInvoker(invoker, worker, inputData);
  }

  @SuppressWarnings("unchecked")
  private HandlerResult executeWithInvoker(
      WorkerAgentInvoker invoker, Worker worker, Object inputData) {

    int timeoutMs =
        worker.executionPolicy().timeoutMs() != null
            ? worker.executionPolicy().timeoutMs()
            : 30_000;

    AtomicReference<AgentEvent.InvocationComplete> invocationResultRef = new AtomicReference<>();

    try {
      Future<WorkerResult<?>> future =
          virtualThreads.submit(
              () -> {
                var response = invoker.executeDetailed((Map<String, Object>) inputData);
                invocationResultRef.set(response.invocationResult());
                return response.result();
              });
      WorkerResult<?> result = future.get(timeoutMs, TimeUnit.MILLISECONDS);

      AgentEvent.InvocationComplete ir = invocationResultRef.get();
      if (ir != null) {
        return new HandlerResult(
            result,
            Map.of(
                "inputTokens", ir.inputTokens(),
                "outputTokens", ir.outputTokens(),
                "thinkingTokens", ir.thinkingTokens(),
                "cacheReadTokens", ir.cacheReadTokens(),
                "cacheWriteTokens", ir.cacheWriteTokens(),
                "totalCostUsd", ir.totalCostUsd() != null ? ir.totalCostUsd() : 0.0,
                "durationMs", ir.durationMs(),
                "backendKey", ir.backendKey() != null ? ir.backendKey() : "",
                "instanceId", ir.instanceId() != null ? ir.instanceId() : ""));
      }
      return new HandlerResult(result);
    } catch (TimeoutException e) {
      return new HandlerResult(WorkerResult.expired("Agent timed out after " + timeoutMs + "ms"));
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException(cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Agent execution interrupted", e);
    }
  }
}
