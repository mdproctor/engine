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
package io.casehub.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.model.WorkerContext;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.HandlerResult;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpWorkerFunctionHandlerTest {

  private McpSyncClient mockClient;
  private McpWorkerFunctionHandler handler;

  @BeforeEach
  void setUp() {
    mockClient = mock(McpSyncClient.class);
    McpClientRegistry registry =
        new McpClientRegistry() {
          @Override
          McpSyncClient createClient(final McpTransport transport) {
            return mockClient;
          }
        };
    handler = new McpWorkerFunctionHandler(registry, Executors.newVirtualThreadPerTaskExecutor());
  }

  @Test
  void supportsMcpWorkerFunction() {
    var fn =
        new McpWorkerFunction(new McpTransport.Stdio(List.of("/bin/s"), Map.of()), "read-file");
    assertThat(handler.supports(fn)).isTrue();
  }

  @Test
  void doesNotSupportOtherFunctions() {
    assertThat(handler.supports(WorkerFunction.NONE)).isFalse();
  }

  @Test
  void callToolReturnsCompletedResult() {
    when(mockClient.callTool(any()))
        .thenReturn(
            new CallToolResult(
                List.of(new TextContent(null, "{\"result\":\"done\"}", null)), false, null, null));

    HandlerResult result = executeHandler("read-file");

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Completed.class);
    assertThat((Map<String, Object>) result.result().output()).containsEntry("result", "done");
  }

  @Test
  void callToolReturnsFailedOnIsError() {
    when(mockClient.callTool(any()))
        .thenReturn(
            new CallToolResult(
                List.of(new TextContent(null, "Tool failed: bad input", null)), true, null, null));

    HandlerResult result = executeHandler("read-file");

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Failed.class);
  }

  @Test
  void callToolHandlesNonJsonTextContent() {
    when(mockClient.callTool(any()))
        .thenReturn(
            new CallToolResult(
                List.of(new TextContent(null, "plain text response", null)), false, null, null));

    HandlerResult result = executeHandler("read-file");

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Completed.class);
    assertThat((Map<String, Object>) result.result().output())
        .containsEntry("text", "plain text response");
  }

  @Test
  void callToolConcatenatesMultipleTextParts() {
    when(mockClient.callTool(any()))
        .thenReturn(
            new CallToolResult(
                List.of(
                    new TextContent(null, "{\"a\":1,", null),
                    new TextContent(null, "\"b\":2}", null)),
                false,
                null,
                null));

    HandlerResult result = executeHandler("read-file");

    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Completed.class);
    assertThat((Map<String, Object>) result.result().output())
        .containsEntry("a", 1)
        .containsEntry("b", 2);
  }

  @Test
  void transientErrorPropagatesAsException() {
    when(mockClient.callTool(any()))
        .thenThrow(new RuntimeException(new IOException("conn refused")));

    assertThatThrownBy(() -> executeHandler("read-file")).isInstanceOf(RuntimeException.class);
  }

  @Test
  void protocolMetadataIncludesMcpFields() {
    when(mockClient.callTool(any()))
        .thenReturn(
            new CallToolResult(List.of(new TextContent(null, "{}", null)), false, null, null));

    HandlerResult result = executeHandler("read-file");

    assertThat(result.protocolMetadata()).containsEntry("mcpTool", "read-file");
    assertThat(result.protocolMetadata()).containsKey("mcpServer");
    assertThat(result.protocolMetadata()).containsEntry("mcpTransport", "stdio");
    assertThat(result.protocolMetadata()).containsKey("mcpDuration");
  }

  private HandlerResult executeHandler(final String toolName) {
    var transport = new McpTransport.Stdio(List.of("/bin/server"), Map.of());
    var fn = new McpWorkerFunction(transport, toolName);
    var context = new WorkerContext("Test task", UUID.randomUUID(), null, null, null, null);
    var metadata = new ExecutionMetadata("test-worker", "hash-abc");
    return handler.execute(fn, Map.of("input", "data"), context, 30000, metadata);
  }
}
