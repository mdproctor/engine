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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.WorkerContext;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.HandlerResult;
import io.casehub.engine.common.internal.executor.WorkerFunctionHandler;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jboss.logging.Logger;

@ApplicationScoped
public class McpWorkerFunctionHandler implements WorkerFunctionHandler {

  private static final Logger LOG = Logger.getLogger(McpWorkerFunctionHandler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final McpClientRegistry clientRegistry;
  private final ExecutorService virtualThreads;

  @Inject
  public McpWorkerFunctionHandler(
      final McpClientRegistry clientRegistry,
      @VirtualThreads final ExecutorService virtualThreads) {
    this.clientRegistry = clientRegistry;
    this.virtualThreads = virtualThreads;
  }

  @Override
  public boolean supports(final WorkerFunction<?, ?> function) {
    return function instanceof McpWorkerFunction;
  }

  @Override
  @SuppressWarnings("unchecked")
  public HandlerResult execute(
      final WorkerFunction<?, ?> function,
      final Object inputData,
      final WorkerContext context,
      final int timeoutMs,
      final ExecutionMetadata metadata) {
    final McpWorkerFunction mcp = (McpWorkerFunction) function;
    final Map<String, Object> input =
        inputData instanceof Map ? (Map<String, Object>) inputData : Map.of();

    final McpSyncClient client = clientRegistry.getOrCreate(mcp.transport());
    final long startNanos = System.nanoTime();

    final Future<HandlerResult> future =
        virtualThreads.submit(() -> callTool(client, mcp, input, startNanos));

    try {
      return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      return new HandlerResult(
          WorkerResult.expired("MCP tool timed out after " + timeoutMs + "ms"),
          buildMetadata(mcp, startNanos));
    } catch (ExecutionException e) {
      final Throwable cause = e.getCause();
      if (isTransient(cause)) {
        clientRegistry.evict(mcp.transport());
        throw new RuntimeException(cause);
      }
      return new HandlerResult(
          WorkerResult.failed(cause != null ? cause.getMessage() : e.getMessage()),
          buildMetadata(mcp, startNanos));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("MCP execution interrupted", e);
    }
  }

  private HandlerResult callTool(
      final McpSyncClient client,
      final McpWorkerFunction mcp,
      final Map<String, Object> input,
      final long startNanos) {
    final CallToolRequest request =
        CallToolRequest.builder(mcp.toolName()).arguments(input).build();
    final CallToolResult result = client.callTool(request);
    return new HandlerResult(mapResult(result), buildMetadata(mcp, startNanos));
  }

  @SuppressWarnings("unchecked")
  private WorkerResult<?> mapResult(final CallToolResult result) {
    final String textContent = extractTextContent(result);

    if (Boolean.TRUE.equals(result.isError())) {
      return WorkerResult.failed(textContent != null ? textContent : "MCP tool execution failed");
    }

    if (textContent == null) {
      return WorkerResult.completed(Map.of());
    }

    try {
      final Map<String, Object> parsed = MAPPER.readValue(textContent, Map.class);
      return WorkerResult.completed(parsed);
    } catch (Exception e) {
      return WorkerResult.completed(Map.of("text", textContent));
    }
  }

  private String extractTextContent(final CallToolResult result) {
    if (result.content() == null || result.content().isEmpty()) {
      return null;
    }
    final StringBuilder text = new StringBuilder();
    for (final Content content : result.content()) {
      if (content instanceof TextContent tc && tc.text() != null) {
        text.append(tc.text());
      }
    }
    return text.isEmpty() ? null : text.toString();
  }

  private Map<String, Object> buildMetadata(final McpWorkerFunction mcp, final long startNanos) {
    final Map<String, Object> metadata = new LinkedHashMap<>();
    final String serverIdentity =
        switch (mcp.transport()) {
          case McpTransport.Stdio stdio -> String.join(" ", stdio.command());
          case McpTransport.Http http -> http.url();
        };
    metadata.put("mcpServer", serverIdentity);
    metadata.put("mcpTool", mcp.toolName());
    metadata.put("mcpTransport", mcp.transport() instanceof McpTransport.Stdio ? "stdio" : "http");
    metadata.put("mcpDuration", (System.nanoTime() - startNanos) / 1_000_000);
    return metadata;
  }

  private boolean isTransient(Throwable t) {
    while (t != null) {
      if (t instanceof java.io.IOException) {
        return true;
      }
      t = t.getCause();
    }
    return false;
  }
}
