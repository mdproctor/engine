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
package io.casehub.engine.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.WorkerContext;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.HandlerResult;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class A2AWorkerFunctionHandlerTest {

  private MockWebServer server;
  private A2AWorkerFunctionHandler handler;
  private A2AClientRegistry registry;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    registry = new A2AClientRegistry();
    handler =
        new A2AWorkerFunctionHandler(
            registry, Executors.newVirtualThreadPerTaskExecutor(), 100, 10_485_760);
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void supportsA2AWorkerFunction() {
    var fn = new A2AWorkerFunction("https://example.com", null, false, A2AAuthConfig.NONE);
    assertThat(handler.supports(fn)).isTrue();
  }

  @Test
  void doesNotSupportOtherFunctions() {
    assertThat(handler.supports(WorkerFunction.NONE)).isFalse();
  }

  @Test
  void syncSendReturnsCompletedResult() {
    server.enqueue(
        new MockResponse()
            .setBody(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"id\":\"task-1\",\"status\":{\"state\":\"completed\"},\"artifacts\":[{\"parts\":[{\"type\":\"text\",\"text\":\"{\\\"analysisResult\\\":\\\"clean\\\"}\"}]}]}}")
            .addHeader("Content-Type", "application/json"));

    HandlerResult result = executeHandler(false);

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Completed.class);
    assertThat((Map<String, Object>) result.result().output())
        .containsEntry("analysisResult", "clean");
  }

  @Test
  void syncSendReturnsFailed() {
    server.enqueue(
        new MockResponse()
            .setBody(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"id\":\"task-2\",\"status\":{\"state\":\"failed\",\"message\":{\"parts\":[{\"type\":\"text\",\"text\":\"Agent error\"}]}}}}")
            .addHeader("Content-Type", "application/json"));

    HandlerResult result = executeHandler(false);

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Failed.class);
  }

  @Test
  void syncSendReturnsInputRequiredAsFailed() {
    server.enqueue(
        new MockResponse()
            .setBody(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"id\":\"task-3\",\"status\":{\"state\":\"input_required\"}}}")
            .addHeader("Content-Type", "application/json"));

    HandlerResult result = executeHandler(false);

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Failed.class);
  }

  @Test
  void transientErrorPropagatesAsException() {
    server.enqueue(new MockResponse().setResponseCode(503));

    assertThatThrownBy(() -> executeHandler(false))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(java.io.IOException.class);
  }

  @Test
  void protocolMetadataIncludesA2aFields() {
    server.enqueue(
        new MockResponse()
            .setBody(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"id\":\"task-42\",\"status\":{\"state\":\"completed\"},\"artifacts\":[]}}")
            .addHeader("Content-Type", "application/json"));

    HandlerResult result = executeHandler(false);

    assertThat(result.protocolMetadata()).containsKey("a2aEndpoint");
    assertThat(result.protocolMetadata()).containsEntry("a2aTaskId", "task-42");
    assertThat(result.protocolMetadata()).containsKey("a2aMessageId");
    assertThat(result.protocolMetadata()).containsEntry("a2aStreaming", false);
  }

  @Test
  void messageIdIsDeterministic() {
    server.enqueue(
        new MockResponse()
            .setBody(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"id\":\"t\",\"status\":{\"state\":\"completed\"},\"artifacts\":[]}}")
            .addHeader("Content-Type", "application/json"));

    HandlerResult result = executeHandler(false);

    String messageId = (String) result.protocolMetadata().get("a2aMessageId");
    assertThat(messageId).startsWith("casehub:");
    assertThat(messageId).contains("test-worker");
    assertThat(messageId).contains("hash-abc");
  }

  @Test
  void streamingReturnsCompletedResult() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"id\":\"task-1\",\"status\":{\"state\":\"working\"}}\n\n"
                    + "data: {\"id\":\"task-1\",\"status\":{\"state\":\"completed\"},\"artifacts\":[{\"parts\":[{\"type\":\"text\",\"text\":\"{\\\"result\\\":\\\"done\\\"}\"}],\"index\":0}]}\n\n"));

    HandlerResult result = executeHandler(true);

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Completed.class);
    assertThat((Map<String, Object>) result.result().output()).containsEntry("result", "done");
  }

  @Test
  void streamingAccumulatesArtifactsByIndex() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"id\":\"t\",\"artifact\":{\"parts\":[{\"type\":\"text\",\"text\":\"{\\\"a\\\":1}\"}],\"index\":0}}\n\n"
                    + "data: {\"id\":\"t\",\"artifact\":{\"parts\":[{\"type\":\"text\",\"text\":\"{\\\"b\\\":2}\"}],\"index\":1}}\n\n"
                    + "data: {\"id\":\"t\",\"status\":{\"state\":\"completed\"}}\n\n"));

    HandlerResult result = executeHandler(true);

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Completed.class);
    assertThat((Map<String, Object>) result.result().output())
        .containsEntry("a", 1)
        .containsEntry("b", 2);
  }

  @Test
  void streamingAppendsToExistingArtifact() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"id\":\"t\",\"artifact\":{\"parts\":[{\"type\":\"text\",\"text\":\"{\\\"partial\\\":\"}],\"index\":0}}\n\n"
                    + "data: {\"id\":\"t\",\"artifact\":{\"parts\":[{\"type\":\"text\",\"text\":\"\\\"done\\\"}\"}],\"index\":0,\"append\":true}}\n\n"
                    + "data: {\"id\":\"t\",\"status\":{\"state\":\"completed\"}}\n\n"));

    HandlerResult result = executeHandler(true);

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Completed.class);
    assertThat((Map<String, Object>) result.result().output()).containsEntry("partial", "done");
  }

  @Test
  void streamingReturnsFailedOnRemoteFailure() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"id\":\"t\",\"status\":{\"state\":\"working\"}}\n\n"
                    + "data: {\"id\":\"t\",\"status\":{\"state\":\"failed\",\"message\":{\"parts\":[{\"type\":\"text\",\"text\":\"Agent error\"}]}}}\n\n"));

    HandlerResult result = executeHandler(true);

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Failed.class);
  }

  @Test
  void streamingReturnsFailedOnCancel() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"id\":\"t\",\"status\":{\"state\":\"working\"}}\n\n"
                    + "data: {\"id\":\"t\",\"status\":{\"state\":\"canceled\"}}\n\n"));

    HandlerResult result = executeHandler(true);

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Failed.class);
  }

  @Test
  void streamingMetadataIncludesStatusTransitions() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"id\":\"task-1\",\"status\":{\"state\":\"working\"}}\n\n"
                    + "data: {\"id\":\"task-1\",\"status\":{\"state\":\"completed\"},\"artifacts\":[]}\n\n"));

    HandlerResult result = executeHandler(true);

    assertThat(result.protocolMetadata()).containsKey("a2aStatusTransitions");
    @SuppressWarnings("unchecked")
    List<String> transitions = (List<String>) result.protocolMetadata().get("a2aStatusTransitions");
    assertThat(transitions).containsExactly("working", "completed");
    assertThat(result.protocolMetadata()).containsEntry("a2aStreaming", true);
    assertThat(result.protocolMetadata()).containsEntry("a2aTaskId", "task-1");
  }

  @Test
  void streamingBoundsExceededOnArtifactCount() {
    var smallHandler =
        new A2AWorkerFunctionHandler(
            registry, Executors.newVirtualThreadPerTaskExecutor(), 1, 10_485_760);
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"id\":\"t\",\"artifact\":{\"parts\":[{\"type\":\"text\",\"text\":\"a\"}],\"index\":0}}\n\n"
                    + "data: {\"id\":\"t\",\"artifact\":{\"parts\":[{\"type\":\"text\",\"text\":\"b\"}],\"index\":1}}\n\n"
                    + "data: {\"id\":\"t\",\"status\":{\"state\":\"completed\"}}\n\n"));

    String endpoint = server.url("/").toString();
    var fn = new A2AWorkerFunction(endpoint, null, true, A2AAuthConfig.NONE);
    var context = new WorkerContext("Test task", UUID.randomUUID(), null, null, null, null);
    var metadata = new ExecutionMetadata("test-worker", "hash-abc");
    HandlerResult result =
        smallHandler.execute(fn, Map.of("input", "data"), context, 30000, metadata);

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Failed.class);
  }

  @Test
  void streamingBoundsExceededOnArtifactBytes() {
    var smallHandler =
        new A2AWorkerFunctionHandler(
            registry, Executors.newVirtualThreadPerTaskExecutor(), 100, 10);
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"id\":\"t\",\"artifact\":{\"parts\":[{\"type\":\"text\",\"text\":\"this text is longer than ten bytes\"}],\"index\":0}}\n\n"
                    + "data: {\"id\":\"t\",\"status\":{\"state\":\"completed\"}}\n\n"));

    String endpoint = server.url("/").toString();
    var fn = new A2AWorkerFunction(endpoint, null, true, A2AAuthConfig.NONE);
    var context = new WorkerContext("Test task", UUID.randomUUID(), null, null, null, null);
    var metadata = new ExecutionMetadata("test-worker", "hash-abc");
    HandlerResult result =
        smallHandler.execute(fn, Map.of("input", "data"), context, 30000, metadata);

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Failed.class);
  }

  @Test
  void streamingIgnoresEventsAfterTerminal() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"id\":\"t\",\"status\":{\"state\":\"completed\"},\"artifacts\":[{\"parts\":[{\"type\":\"text\",\"text\":\"{\\\"result\\\":1}\"}],\"index\":0}]}\n\n"
                    + "data: {\"id\":\"t\",\"artifact\":{\"parts\":[{\"type\":\"text\",\"text\":\"{\\\"extra\\\":2}\"}],\"index\":1}}\n\n"));

    HandlerResult result = executeHandler(true);

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Completed.class);
    assertThat((Map<String, Object>) result.result().output()).containsEntry("result", 1);
    assertThat((Map<String, Object>) result.result().output()).doesNotContainKey("extra");
  }

  @Test
  void streamingFailsWhenStreamClosesWithoutTerminal() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"id\":\"t\",\"status\":{\"state\":\"working\"}}\n\n"));

    HandlerResult result = executeHandler(true);

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Failed.class);
  }

  private HandlerResult executeHandler(final boolean streaming) {
    String endpoint = server.url("/").toString();
    var fn = new A2AWorkerFunction(endpoint, null, streaming, A2AAuthConfig.NONE);
    var context = new WorkerContext("Test task", UUID.randomUUID(), null, null, null, null);
    var metadata = new ExecutionMetadata("test-worker", "hash-abc");
    return handler.execute(fn, Map.of("input", "data"), context, 30000, metadata);
  }
}
