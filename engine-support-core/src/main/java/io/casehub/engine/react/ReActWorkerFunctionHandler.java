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
import io.casehub.api.engine.WorkerRuntime;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.ai.TokenUsage;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.HandlerResult;
import io.casehub.engine.common.internal.executor.WorkerFunctionHandler;
import io.casehub.engine.runtime.executor.WorkerRuntimeFactory;
import io.casehub.platform.agent.react.TerminationReason;
import io.casehub.platform.agent.react.Tool;
import io.casehub.platform.agent.react.ToolUseConfig;
import io.casehub.platform.agent.react.ToolUseListener;
import io.casehub.platform.agent.react.ToolUseLoop;
import io.casehub.platform.agent.react.ToolUseResult;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ReActWorkerFunctionHandler implements WorkerFunctionHandler {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final System.Logger LOG =
      System.getLogger(ReActWorkerFunctionHandler.class.getName());

  private final WorkerRuntimeFactory runtimeFactory;
  private final EventDispatcher eventDispatcher;
  private final ExecutorService executor;
  private final ToolUseLoop toolUseLoop = new ToolUseLoop();

  public ReActWorkerFunctionHandler(
      WorkerRuntimeFactory runtimeFactory,
      EventDispatcher eventDispatcher,
      ExecutorService executor) {
    this.runtimeFactory = runtimeFactory;
    this.eventDispatcher = eventDispatcher;
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

    List<Tool> tools = buildTools(reactFn.tools(), runtime);

    ToolUseListener listener =
        event -> {
          var engineToolCalls =
              event.toolCalls().stream()
                  .map(
                      tc ->
                          new ToolCallRecord(
                              tc.name(),
                              parseArgs(tc.arguments()),
                              parseArgs(tc.output()),
                              tc.error() ? "error" : resolveSourceType(tc.name(), reactFn.tools()),
                              tc.duration()))
                  .toList();

          var tokenUsage = new TokenUsage((int) event.inputTokens(), (int) event.outputTokens());

          var cycleEvent =
              new ReActCycleEvent(
                  context.caseId(),
                  metadata.workerName(),
                  metadata.tenancyId(),
                  event.cycleIndex(),
                  event.reasoningText(),
                  engineToolCalls,
                  tokenUsage);

          try {
            var json = new io.vertx.core.json.JsonObject(MAPPER.writeValueAsString(cycleEvent));
            eventDispatcher.dispatch(json);
          } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to publish ReActCycleEvent", e);
          }
        };

    var config =
        new ToolUseConfig(
            reactFn.model(), reactFn.systemPrompt(), tools, reactFn.maxCycles(), listener);

    ToolUseResult result = toolUseLoop.run(config, formatInput(inputData));

    if (result.reason() == TerminationReason.COMPLETED) {
      var toolsUsed = new LinkedHashSet<String>();
      result.toolCalls().forEach(tc -> toolsUsed.add(tc.name()));
      return toCompletedResult(
          result.text(),
          result.cycleCount(),
          result.inputTokens(),
          result.outputTokens(),
          toolsUsed,
          result.elapsed());
    }

    return toExpiredResult(
        result.reason() == TerminationReason.INTERRUPTED ? "cancelled" : "maxCycles",
        reactFn,
        result.cycleCount());
  }

  private List<Tool> buildTools(List<ToolSource> sources, WorkerRuntime runtime) {
    return sources.stream()
        .map(
            source ->
                switch (source) {
                  case ToolSource.WorkerTool wt -> {
                    var builder =
                        Tool.builder()
                            .name(wt.capability().name())
                            .description(wt.capability().description());
                    for (var field :
                        ToolSpecificationBuilder.extractFieldNames(
                            wt.capability().inputProjection())) {
                      builder.stringParameter(field);
                    }
                    yield builder
                        .executor(
                            (name, argsJson) -> {
                              var args = parseArgs(argsJson);
                              var workerResult = runtime.execute(wt.workerName(), args);
                              return serializeOutput(extractOutput(workerResult));
                            })
                        .build();
                  }
                  case ToolSource.LocalTool lt -> {
                    var builder = Tool.builder().name(lt.name()).description(lt.description());
                    if (lt.parameterSchema() != null) {
                      for (var key : lt.parameterSchema().keySet()) {
                        builder.stringParameter(key);
                      }
                    }
                    yield builder
                        .executor(
                            (name, argsJson) -> {
                              var args = parseArgs(argsJson);
                              var output = lt.fn().apply(args);
                              return serializeOutput(output);
                            })
                        .build();
                  }
                })
        .toList();
  }

  private String resolveSourceType(String toolName, List<ToolSource> sources) {
    return sources.stream()
        .filter(s -> s.name().equals(toolName))
        .findFirst()
        .map(s -> s instanceof ToolSource.WorkerTool ? "worker" : "local")
        .orElse("unknown");
  }

  private HandlerResult toCompletedResult(
      String text,
      int cycleCount,
      long totalInputTokens,
      long totalOutputTokens,
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
      return Map.of("raw", arguments);
    }
  }

  private String serializeOutput(Map<String, Object> output) {
    try {
      return MAPPER.writeValueAsString(output);
    } catch (JsonProcessingException e) {
      return output.toString();
    }
  }
}
