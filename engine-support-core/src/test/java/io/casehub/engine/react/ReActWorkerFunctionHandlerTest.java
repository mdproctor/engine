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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.api.engine.WorkerRuntime;
import io.casehub.api.model.WorkerContext;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.internal.executor.WorkerRuntimeFactory;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import io.vertx.core.eventbus.EventBus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReActWorkerFunctionHandlerTest {

  private WorkerRuntimeFactory runtimeFactory;
  private WorkerRuntime runtime;
  private EventBus eventBus;
  private ReActWorkerFunctionHandler handler;

  @BeforeEach
  void setUp() {
    runtimeFactory = mock(WorkerRuntimeFactory.class);
    runtime = mock(WorkerRuntime.class);
    eventBus = mock(EventBus.class);
    when(runtimeFactory.create(any(), any(), any())).thenReturn(runtime);
    handler =
        new ReActWorkerFunctionHandler(
            runtimeFactory, eventBus, Executors.newVirtualThreadPerTaskExecutor());
  }

  @Test
  void supportsReActWorkerFunction() {
    var cap = new Capability("s", ".", ".", "d");
    var fn = new ReActWorkerFunction(null, "p", List.of(new ToolSource.WorkerTool(cap, "w")));
    assertThat(handler.supports(fn)).isTrue();
  }

  @Test
  void doesNotSupportOtherFunctions() {
    assertThat(handler.supports(mock(io.casehub.worker.api.WorkerFunction.class))).isFalse();
  }

  @Test
  void runsToolUseLoopAndReturnsFinalAnswer() {
    var chatModel = mock(ChatModel.class);

    var toolRequest =
        ToolExecutionRequest.builder()
            .id("call-1")
            .name("web-search")
            .arguments("{\"query\":\"test\"}")
            .build();
    var aiWithTool = AiMessage.from("I should search for test", List.of(toolRequest));
    var responseWithTool = ChatResponse.builder().aiMessage(aiWithTool).build();

    var aiFinal = AiMessage.from("{\"answer\": \"found it\"}");
    var responseFinal = ChatResponse.builder().aiMessage(aiFinal).build();

    when(chatModel.chat(any(ChatRequest.class)))
        .thenReturn(responseWithTool)
        .thenReturn(responseFinal);

    doReturn(WorkerResult.of(Map.of("results", "data")))
        .when(runtime)
        .execute(eq("search-worker"), any());

    var cap = new Capability("web-search", ".query", ".results", "Search");
    var fn =
        new ReActWorkerFunction(
            chatModel,
            "You are an analyst",
            List.of(new ToolSource.WorkerTool(cap, "search-worker")));

    var context = mock(WorkerContext.class);
    when(context.caseId()).thenReturn(UUID.randomUUID());
    var metadata = new ExecutionMetadata("analyst", null, null, null, null);

    var result = handler.execute(fn, Map.of("query", "test"), context, 30000, metadata);

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Success.class);
    assertThat(result.result().output()).isNotNull();
    verify(runtime).execute(eq("search-worker"), any());
    verify(eventBus).publish(eq("casehub.react.cycle"), any(io.vertx.core.json.JsonObject.class));
  }

  @Test
  void hallucinatedToolNameReturnsErrorMessageToLlm() {
    var chatModel = mock(ChatModel.class);

    var badRequest =
        ToolExecutionRequest.builder()
            .id("call-1")
            .name("nonexistent-tool")
            .arguments("{}")
            .build();
    var aiWithBadTool = AiMessage.from("Let me try this", List.of(badRequest));
    var aiFinal = AiMessage.from("{\"answer\": \"gave up\"}");

    when(chatModel.chat(any(ChatRequest.class)))
        .thenReturn(ChatResponse.builder().aiMessage(aiWithBadTool).build())
        .thenReturn(ChatResponse.builder().aiMessage(aiFinal).build());

    var cap = new Capability("search", ".", ".", "Search");
    var fn =
        new ReActWorkerFunction(
            chatModel, "prompt", List.of(new ToolSource.WorkerTool(cap, "searcher")));

    var context = mock(WorkerContext.class);
    when(context.caseId()).thenReturn(UUID.randomUUID());

    var result =
        handler.execute(
            fn, Map.of(), context, 30000, new ExecutionMetadata("w", null, null, null, null));

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Success.class);
    verify(runtime, never()).execute(any(String.class), any());
  }

  @Test
  void maxCyclesExceededReturnsExpired() {
    var chatModel = mock(ChatModel.class);

    var toolReq =
        ToolExecutionRequest.builder().id("call-1").name("search").arguments("{}").build();
    var aiWithTool = AiMessage.from("searching", List.of(toolReq));
    when(chatModel.chat(any(ChatRequest.class)))
        .thenReturn(ChatResponse.builder().aiMessage(aiWithTool).build());
    doReturn(WorkerResult.of(Map.of())).when(runtime).execute(eq("searcher"), any());

    var cap = new Capability("search", ".", ".", "Search");
    var fn =
        new ReActWorkerFunction(
            chatModel, "prompt", List.of(new ToolSource.WorkerTool(cap, "searcher")), 3);

    var context = mock(WorkerContext.class);
    when(context.caseId()).thenReturn(UUID.randomUUID());

    var result =
        handler.execute(
            fn, Map.of(), context, 30000, new ExecutionMetadata("w", null, null, null, null));

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Expired.class);
    assertThat(result.protocolMetadata()).containsEntry("reactExpiredReason", "maxCycles");
    assertThat(result.protocolMetadata()).containsEntry("reactCycleCount", 3);
  }

  @Test
  void localToolInvocationWorks() {
    var chatModel = mock(ChatModel.class);

    var toolReq =
        ToolExecutionRequest.builder()
            .id("call-1")
            .name("calculate")
            .arguments("{\"expression\":\"2+2\"}")
            .build();
    var aiWithTool = AiMessage.from("I need to calculate", List.of(toolReq));
    var aiFinal = AiMessage.from("{\"result\": 4}");

    when(chatModel.chat(any(ChatRequest.class)))
        .thenReturn(ChatResponse.builder().aiMessage(aiWithTool).build())
        .thenReturn(ChatResponse.builder().aiMessage(aiFinal).build());

    var localTool =
        new ToolSource.LocalTool(
            "calculate",
            "Run a calculation",
            args -> Map.of("result", 4),
            Map.of("expression", Map.of("type", "string")));

    var fn = new ReActWorkerFunction(chatModel, "prompt", List.of(localTool));

    var context = mock(WorkerContext.class);
    when(context.caseId()).thenReturn(UUID.randomUUID());

    var result =
        handler.execute(
            fn, Map.of(), context, 30000, new ExecutionMetadata("w", null, null, null, null));

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Success.class);
    verify(runtime, never()).execute(any(String.class), any());
  }

  @Test
  void llmFailureReturnsFailedResult() {
    var chatModel = mock(ChatModel.class);
    when(chatModel.chat(any(ChatRequest.class)))
        .thenThrow(new RuntimeException("API rate limit exceeded"));

    var cap = new Capability("s", ".", ".", "d");
    var fn =
        new ReActWorkerFunction(chatModel, "prompt", List.of(new ToolSource.WorkerTool(cap, "w")));

    var context = mock(WorkerContext.class);
    when(context.caseId()).thenReturn(UUID.randomUUID());

    var result =
        handler.execute(
            fn, Map.of(), context, 30000, new ExecutionMetadata("w", null, null, null, null));

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Failed.class);
  }

  @Test
  void protocolMetadataContainsAggregatedTokenCounts() {
    var chatModel = mock(ChatModel.class);

    var aiFinal = AiMessage.from("{\"answer\": \"done\"}");
    var tokenUsage = new dev.langchain4j.model.output.TokenUsage(100, 50);
    var response = ChatResponse.builder().aiMessage(aiFinal).tokenUsage(tokenUsage).build();

    when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

    var cap = new Capability("s", ".", ".", "d");
    var fn =
        new ReActWorkerFunction(chatModel, "prompt", List.of(new ToolSource.WorkerTool(cap, "w")));

    var context = mock(WorkerContext.class);
    when(context.caseId()).thenReturn(UUID.randomUUID());

    var result =
        handler.execute(
            fn, Map.of(), context, 30000, new ExecutionMetadata("w", null, null, null, null));

    assertThat(result.protocolMetadata()).containsEntry("reactTotalInputTokens", 100);
    assertThat(result.protocolMetadata()).containsEntry("reactTotalOutputTokens", 50);
    assertThat(result.protocolMetadata()).containsEntry("reactCycleCount", 0);
  }
}
