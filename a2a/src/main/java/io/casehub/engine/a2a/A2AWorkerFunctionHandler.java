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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.WorkerContext;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.HandlerResult;
import io.casehub.engine.common.internal.executor.WorkerFunctionHandler;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class A2AWorkerFunctionHandler implements WorkerFunctionHandler {

  private static final Logger LOG = Logger.getLogger(A2AWorkerFunctionHandler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final A2AClientRegistry clientRegistry;
  private final ExecutorService virtualThreads;
  private final int maxArtifacts;
  private final long maxArtifactBytes;

  @Inject
  public A2AWorkerFunctionHandler(
      final A2AClientRegistry clientRegistry,
      @VirtualThreads final ExecutorService virtualThreads,
      @ConfigProperty(name = "casehub.a2a.max-artifacts", defaultValue = "100")
          final int maxArtifacts,
      @ConfigProperty(name = "casehub.a2a.max-artifact-bytes", defaultValue = "10485760")
          final long maxArtifactBytes) {
    this.clientRegistry = clientRegistry;
    this.virtualThreads = virtualThreads;
    this.maxArtifacts = maxArtifacts;
    this.maxArtifactBytes = maxArtifactBytes;
  }

  @Override
  public boolean supports(final WorkerFunction<?, ?> function) {
    return function instanceof A2AWorkerFunction;
  }

  @Override
  @SuppressWarnings("unchecked")
  public HandlerResult execute(
      final WorkerFunction<?, ?> function,
      final Object inputData,
      final WorkerContext context,
      final int timeoutMs,
      final ExecutionMetadata metadata) {
    final A2AWorkerFunction a2a = (A2AWorkerFunction) function;
    final Map<String, Object> input =
        inputData instanceof Map ? (Map<String, Object>) inputData : Map.of();
    final String messageId =
        "casehub:"
            + context.caseId()
            + ":"
            + metadata.workerName()
            + ":"
            + metadata.inputDataHash();
    final A2AClient client = clientRegistry.getOrCreate(a2a.endpoint(), a2a.auth());

    if (a2a.streaming()) {
      return executeStreamingWithTimeout(client, input, a2a, messageId, timeoutMs);
    }
    return executeSyncWithTimeout(client, input, a2a, messageId, timeoutMs);
  }

  private HandlerResult executeSyncWithTimeout(
      final A2AClient client,
      final Map<String, Object> input,
      final A2AWorkerFunction a2a,
      final String messageId,
      final int timeoutMs) {
    final Future<HandlerResult> future =
        virtualThreads.submit(() -> executeSync(client, input, a2a, messageId));
    return awaitResult(future, a2a, null, messageId, timeoutMs, null);
  }

  private HandlerResult executeStreamingWithTimeout(
      final A2AClient client,
      final Map<String, Object> input,
      final A2AWorkerFunction a2a,
      final String messageId,
      final int timeoutMs) {
    final AtomicReference<String> discoveredTaskId = new AtomicReference<>();
    final Future<HandlerResult> future =
        virtualThreads.submit(
            () -> executeStreaming(client, input, a2a, messageId, timeoutMs, discoveredTaskId));
    return awaitResult(future, a2a, client, messageId, timeoutMs, discoveredTaskId);
  }

  private HandlerResult awaitResult(
      final Future<HandlerResult> future,
      final A2AWorkerFunction a2a,
      final A2AClient client,
      final String messageId,
      final int timeoutMs,
      final AtomicReference<String> discoveredTaskId) {
    try {
      return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      final String taskId = discoveredTaskId != null ? discoveredTaskId.get() : null;
      if (taskId != null && client != null) {
        tryCancelRemoteTask(client, taskId);
      }
      return new HandlerResult(
          WorkerResult.expired("Remote A2A task timed out after " + timeoutMs + "ms"),
          buildMetadata(a2a, taskId, messageId));
    } catch (ExecutionException e) {
      final Throwable cause = e.getCause();
      if (cause instanceof java.io.IOException) {
        throw new RuntimeException(cause);
      }
      return new HandlerResult(
          WorkerResult.failed(cause != null ? cause.getMessage() : e.getMessage()),
          buildMetadata(a2a, null, messageId));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("A2A execution interrupted", e);
    }
  }

  private HandlerResult executeSync(
      final A2AClient client,
      final Map<String, Object> input,
      final A2AWorkerFunction a2a,
      final String messageId)
      throws Exception {
    final A2AClient.A2ATaskResult result = client.send(input, a2a.skill(), messageId);
    return new HandlerResult(mapOutcome(result), buildMetadata(a2a, result.taskId(), messageId));
  }

  @SuppressWarnings("unchecked")
  private HandlerResult executeStreaming(
      final A2AClient client,
      final Map<String, Object> input,
      final A2AWorkerFunction a2a,
      final String messageId,
      final int timeoutMs,
      final AtomicReference<String> discoveredTaskId)
      throws Exception {
    final Instant deadline = Instant.now().plusMillis(timeoutMs);
    final List<String> statusTransitions = new ArrayList<>();
    final Map<Integer, StringBuilder> artifacts = new LinkedHashMap<>();
    long totalArtifactBytes = 0;
    String taskId = null;
    String terminalState = null;
    String failureMessage = null;

    try (A2AClient.A2AStreamHandle handle = client.stream(input, a2a.skill(), messageId)) {
      var iterator = handle.eventLines().iterator();
      while (iterator.hasNext()) {
        final String line = iterator.next();
        if (!line.startsWith("data: ")) {
          continue;
        }
        if (Instant.now().isAfter(deadline)) {
          if (taskId != null) {
            tryCancelRemoteTask(client, taskId);
          }
          return new HandlerResult(
              WorkerResult.expired("Remote A2A task timed out after " + timeoutMs + "ms"),
              buildStreamingMetadata(a2a, taskId, messageId, statusTransitions));
        }

        final JsonNode event = MAPPER.readTree(line.substring(6));
        if (event.has("id")) {
          taskId = event.get("id").asText();
          discoveredTaskId.set(taskId);
        }

        if (event.has("status")) {
          final String state = event.get("status").get("state").asText();
          statusTransitions.add(state);
          if (isTerminal(state)) {
            terminalState = state;
            if (event.get("status").has("message")
                && event.get("status").get("message").has("parts")) {
              failureMessage =
                  event.get("status").get("message").get("parts").get(0).get("text").asText();
            }
          }
        }

        try {
          if (event.has("artifact")) {
            totalArtifactBytes =
                accumulateArtifact(event.get("artifact"), artifacts, totalArtifactBytes);
          }
          if (event.has("artifacts")) {
            for (final JsonNode artifact : event.get("artifacts")) {
              totalArtifactBytes = accumulateArtifact(artifact, artifacts, totalArtifactBytes);
            }
          }
        } catch (ArtifactLimitExceededException e) {
          return new HandlerResult(
              WorkerResult.failed(e.getMessage()),
              buildStreamingMetadata(a2a, taskId, messageId, statusTransitions));
        }

        if (terminalState != null) {
          break;
        }
      }
    }

    final Map<String, Object> output = buildStreamingOutput(artifacts);
    final Map<String, Object> metadata =
        buildStreamingMetadata(a2a, taskId, messageId, statusTransitions);

    if (terminalState == null) {
      return new HandlerResult(
          WorkerResult.failed("A2A stream closed without terminal state"), metadata);
    }

    return new HandlerResult(mapTerminalState(terminalState, output, failureMessage), metadata);
  }

  private long accumulateArtifact(
      final JsonNode artifact, final Map<Integer, StringBuilder> artifacts, long totalBytes) {
    final int index = artifact.has("index") ? artifact.get("index").asInt() : 0;
    final boolean append = artifact.has("append") && artifact.get("append").asBoolean();
    final String text = extractArtifactText(artifact);
    if (text == null) {
      return totalBytes;
    }
    if (!append || !artifacts.containsKey(index)) {
      if (!artifacts.containsKey(index) && artifacts.size() >= maxArtifacts) {
        throw new ArtifactLimitExceededException("Artifact count limit exceeded: " + maxArtifacts);
      }
      final StringBuilder existing = artifacts.get(index);
      final long removedBytes = existing != null ? existing.length() : 0;
      artifacts.put(index, new StringBuilder(text));
      totalBytes = totalBytes - removedBytes + text.length();
    } else {
      artifacts.get(index).append(text);
      totalBytes += text.length();
    }
    if (totalBytes > maxArtifactBytes) {
      throw new ArtifactLimitExceededException(
          "Artifact size limit exceeded: " + totalBytes + " > " + maxArtifactBytes);
    }
    return totalBytes;
  }

  private String extractArtifactText(final JsonNode artifact) {
    if (!artifact.has("parts")) {
      return null;
    }
    final StringBuilder text = new StringBuilder();
    for (final JsonNode part : artifact.get("parts")) {
      if ("text".equals(part.get("type").asText())) {
        text.append(part.get("text").asText());
      }
    }
    return text.isEmpty() ? null : text.toString();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> buildStreamingOutput(final Map<Integer, StringBuilder> artifacts) {
    final Map<String, Object> merged = new LinkedHashMap<>();
    for (final StringBuilder artifactText : artifacts.values()) {
      try {
        final Map<String, Object> parsed = MAPPER.readValue(artifactText.toString(), Map.class);
        merged.putAll(parsed);
      } catch (Exception e) {
        merged.put("text", artifactText.toString());
      }
    }
    return merged;
  }

  private void tryCancelRemoteTask(final A2AClient client, final String taskId) {
    try {
      client.cancelTask(taskId);
    } catch (Exception e) {
      LOG.debugf("Best-effort cancel of remote A2A task %s failed: %s", taskId, e.getMessage());
    }
  }

  private boolean isTerminal(final String state) {
    return "completed".equals(state)
        || "failed".equals(state)
        || "canceled".equals(state)
        || "input_required".equals(state);
  }

  private WorkerResult<?> mapTerminalState(
      final String state, final Map<String, Object> output, final String failureMessage) {
    return switch (state) {
      case "completed" -> WorkerResult.completed(output);
      case "failed" ->
          WorkerResult.failed(failureMessage != null ? failureMessage : "Remote A2A agent failed");
      case "canceled" -> WorkerResult.failed("Remote agent cancelled task");
      case "input_required" ->
          WorkerResult.failed("Remote agent requires additional input — not supported");
      default -> WorkerResult.failed("Unknown A2A state: " + state);
    };
  }

  private WorkerResult<?> mapOutcome(final A2AClient.A2ATaskResult result) {
    if ("protocol_error".equals(result.state())) {
      return WorkerResult.failed(result.failureMessage());
    }
    return mapTerminalState(result.state(), result.output(), result.failureMessage());
  }

  private Map<String, Object> buildMetadata(
      final A2AWorkerFunction a2a, final String taskId, final String messageId) {
    final Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("a2aEndpoint", a2a.endpoint());
    if (a2a.skill() != null) {
      metadata.put("a2aSkill", a2a.skill());
    }
    if (taskId != null) {
      metadata.put("a2aTaskId", taskId);
    }
    metadata.put("a2aMessageId", messageId);
    metadata.put("a2aStreaming", a2a.streaming());
    return metadata;
  }

  private Map<String, Object> buildStreamingMetadata(
      final A2AWorkerFunction a2a,
      final String taskId,
      final String messageId,
      final List<String> statusTransitions) {
    final Map<String, Object> metadata = buildMetadata(a2a, taskId, messageId);
    metadata.put("a2aStatusTransitions", statusTransitions);
    return metadata;
  }

  static final class ArtifactLimitExceededException extends RuntimeException {
    ArtifactLimitExceededException(final String message) {
      super(message);
    }
  }
}
