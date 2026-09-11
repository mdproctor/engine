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
package io.casehub.engine.react;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.api.engine.WorkerRuntime;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.ai.TokenUsage;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.HandlerResult;
import io.casehub.engine.common.internal.executor.WorkerFunctionHandler;
import io.casehub.engine.internal.executor.WorkerRuntimeFactory;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
public class ReActWorkerFunctionHandler implements WorkerFunctionHandler {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final System.Logger LOG =
      System.getLogger(ReActWorkerFunctionHandler.class.getName());

  private final WorkerRuntimeFactory runtimeFactory;
  private final EventBus eventBus;
  private final ExecutorService executor;

  @Inject
  public ReActWorkerFunctionHandler(
      WorkerRuntimeFactory runtimeFactory,
      EventBus eventBus,
      @io.quarkus.virtual.threads.VirtualThreads ExecutorService executor) {
    this.runtimeFactory = runtimeFactory;
    this.eventBus = eventBus;
    this.executor = executor;
  }

  @Override
  public boolean supports(WorkerFunction<?, ?> function) {
    return function instanceof ReActWorkerFunction;
  }

  @Override
  public HandlerResult execute(
      WorkerFunction<?, ?> function,
      Object inputData,
      WorkerContext context,
      int timeoutMs,
      ExecutionMetadata metadata) {

    var reactFn = (ReActWorkerFunction) function;
    var future = executor.submit(() -> executeLoop(reactFn, inputData, context, metadata));

    try {
      return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      return toExpiredResult("timeout", reactFn, 0);
    } catch (ExecutionException e) {
      return new HandlerResult(
          WorkerResult.failed(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return toExpiredResult("interrupted", reactFn, 0);
    }
  }

  private HandlerResult executeLoop(
      ReActWorkerFunction reactFn,
      Object inputData,
      WorkerContext context,
      ExecutionMetadata metadata) {

    var runtime = runtimeFactory.create(context.caseId(), metadata.workerName(), context);

    var toolSpecs = ToolSpecificationBuilder.buildAll(reactFn.tools());
    var toolMap = ToolSpecificationBuilder.buildToolMap(reactFn.tools());

    var messages = new ArrayList<ChatMessage>();
    messages.add(SystemMessage.from(reactFn.systemPrompt()));
    messages.add(UserMessage.from(formatInput(inputData)));

    int cycleCount = 0;
    int totalInputTokens = 0;
    int totalOutputTokens = 0;
    var toolsUsed = new LinkedHashSet<String>();
    var loopStart = Instant.now();

    while (cycleCount < reactFn.maxCycles()) {
      if (Thread.interrupted()) {
        return toExpiredResult("cancelled", reactFn, cycleCount);
      }

      var request = ChatRequest.builder().messages(messages).toolSpecifications(toolSpecs).build();

      ChatResponse response;
      try {
        response = reactFn.model().chat(request);
      } catch (Exception e) {
        LOG.log(System.Logger.Level.ERROR, "LLM call failed at cycle " + cycleCount, e);
        return new HandlerResult(WorkerResult.failed("LLM call failed: " + e.getMessage()));
      }

      var aiMessage = response.aiMessage();
      messages.add(aiMessage);

      if (response.tokenUsage() != null) {
        var usage = response.tokenUsage();
        if (usage.inputTokenCount() != null) totalInputTokens += usage.inputTokenCount();
        if (usage.outputTokenCount() != null) totalOutputTokens += usage.outputTokenCount();
      }

      if (!aiMessage.hasToolExecutionRequests()) {
        var elapsed = Duration.between(loopStart, Instant.now());
        return toCompletedResult(
            aiMessage.text(), cycleCount, totalInputTokens, totalOutputTokens, toolsUsed, elapsed);
      }

      var toolResults = executeToolCalls(aiMessage.toolExecutionRequests(), toolMap, runtime);

      for (var msg : toolResults.messages()) {
        messages.add(msg);
      }
      toolResults.results().forEach(r -> toolsUsed.add(r.name()));

      publishCycleEvent(
          context.caseId(), metadata, cycleCount, aiMessage, toolResults, response.tokenUsage());

      cycleCount++;
    }

    return toExpiredResult("maxCycles", reactFn, cycleCount);
  }

  private ToolCallResults executeToolCalls(
      List<ToolExecutionRequest> requests, Map<String, ToolSource> toolMap, WorkerRuntime runtime) {

    var resultMessages = new ArrayList<ToolExecutionResultMessage>();
    var results = new ArrayList<ToolCallRecord>();

    for (var request : requests) {
      var tool = toolMap.get(request.name());

      if (tool == null) {
        var errorMsg = "Unknown tool: " + request.name() + ". Available tools: " + toolMap.keySet();
        resultMessages.add(ToolExecutionResultMessage.from(request, errorMsg));
        results.add(
            new ToolCallRecord(
                request.name(), Map.of(), Map.of("error", errorMsg), "unknown", Duration.ZERO));
        continue;
      }

      var args = parseArgs(request.arguments());
      var start = Instant.now();
      Map<String, Object> output;
      String sourceType;

      try {
        switch (tool) {
          case ToolSource.WorkerTool wt -> {
            var result = runtime.execute(wt.workerName(), args);
            output = extractOutput(result);
            sourceType = "worker";
          }
          case ToolSource.LocalTool lt -> {
            output = lt.fn().apply(args);
            sourceType = "local";
          }
        }
      } catch (Exception e) {
        LOG.log(System.Logger.Level.WARNING, "Tool execution failed: " + request.name(), e);
        output = Map.of("error", e.getMessage() != null ? e.getMessage() : "unknown error");
        sourceType = tool instanceof ToolSource.WorkerTool ? "worker" : "local";
      }

      var duration = Duration.between(start, Instant.now());
      String outputJson;
      try {
        outputJson = MAPPER.writeValueAsString(output);
      } catch (JsonProcessingException e) {
        outputJson = output.toString();
      }
      resultMessages.add(ToolExecutionResultMessage.from(request, outputJson));
      results.add(new ToolCallRecord(request.name(), args, output, sourceType, duration));
    }
    return new ToolCallResults(resultMessages, results);
  }

  private void publishCycleEvent(
      java.util.UUID caseId,
      ExecutionMetadata metadata,
      int cycleIndex,
      AiMessage aiMessage,
      ToolCallResults toolResults,
      dev.langchain4j.model.output.TokenUsage tokenUsage) {

    TokenUsage usage = null;
    if (tokenUsage != null) {
      usage =
          new TokenUsage(
              tokenUsage.inputTokenCount() != null ? tokenUsage.inputTokenCount() : 0,
              tokenUsage.outputTokenCount() != null ? tokenUsage.outputTokenCount() : 0);
    }

    var event =
        new ReActCycleEvent(
            caseId,
            metadata.workerName(),
            metadata.tenancyId(),
            cycleIndex,
            aiMessage.text() != null ? aiMessage.text() : "",
            toolResults.results(),
            usage);

    try {
      var json = new io.vertx.core.json.JsonObject(MAPPER.writeValueAsString(event));
      eventBus.publish(EventBusAddresses.REACT_CYCLE, json);
    } catch (JsonProcessingException e) {
      LOG.log(System.Logger.Level.WARNING, "Failed to serialize ReActCycleEvent", e);
    }
  }

  private HandlerResult toCompletedResult(
      String text,
      int cycleCount,
      int totalInputTokens,
      int totalOutputTokens,
      LinkedHashSet<String> toolsUsed,
      Duration elapsed) {

    Map<String, Object> output;
    if (text == null || text.isBlank()) {
      output = Map.of("answer", "");
    } else {
      try {
        output = MAPPER.readValue(text, MAP_TYPE);
      } catch (Exception e) {
        output = Map.of("answer", text);
      }
    }

    return new HandlerResult(
        WorkerResult.of(output),
        Map.of(
            "reactCycleCount", cycleCount,
            "reactToolsUsed", List.copyOf(toolsUsed),
            "reactTotalDurationMs", elapsed.toMillis(),
            "reactTotalInputTokens", totalInputTokens,
            "reactTotalOutputTokens", totalOutputTokens));
  }

  private HandlerResult toExpiredResult(String reason, ReActWorkerFunction fn, int cycleCount) {
    return new HandlerResult(
        WorkerResult.expired(
            "ReAct " + reason + " after " + cycleCount + " cycles (max: " + fn.maxCycles() + ")"),
        Map.of("reactCycleCount", cycleCount, "reactExpiredReason", reason));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> extractOutput(WorkerResult<?> result) {
    if (result.output() instanceof Map) {
      return (Map<String, Object>) result.output();
    }
    return MAPPER.convertValue(result.output(), MAP_TYPE);
  }

  private String formatInput(Object inputData) {
    try {
      if (inputData instanceof Map) {
        return MAPPER.writeValueAsString(inputData);
      }
      if (inputData instanceof String s) {
        return s;
      }
      return MAPPER.writeValueAsString(inputData);
    } catch (JsonProcessingException e) {
      return inputData.toString();
    }
  }

  private Map<String, Object> parseArgs(String arguments) {
    if (arguments == null || arguments.isBlank()) {
      return Map.of();
    }
    try {
      return MAPPER.readValue(arguments, MAP_TYPE);
    } catch (JsonProcessingException e) {
      LOG.log(System.Logger.Level.WARNING, "Failed to parse tool arguments: " + arguments, e);
      return Map.of();
    }
  }

  record ToolCallResults(List<ToolExecutionResultMessage> messages, List<ToolCallRecord> results) {}
}
