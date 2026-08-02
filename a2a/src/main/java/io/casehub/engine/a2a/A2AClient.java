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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.ConfigProvider;

public class A2AClient implements AutoCloseable {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String endpoint;
  private final A2AAuthConfig auth;
  private final HttpClient httpClient;
  private final AtomicInteger requestIdCounter = new AtomicInteger(1);

  public A2AClient(final String endpoint, final A2AAuthConfig auth) {
    this.endpoint = endpoint.endsWith("/") ? endpoint : endpoint + "/";
    this.auth = auth;
    this.httpClient = HttpClient.newBuilder().build();
  }

  public A2ATaskResult send(
      final Map<String, Object> input, final String skill, final String messageId)
      throws IOException, InterruptedException {
    final ObjectNode request = buildJsonRpcRequest("message/send", input, skill, messageId);
    final HttpRequest httpRequest = buildHttpRequest(request);
    final HttpResponse<String> response =
        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    return handleResponse(response);
  }

  public A2AStreamHandle stream(
      final Map<String, Object> input, final String skill, final String messageId)
      throws IOException, InterruptedException {
    final ObjectNode request = buildJsonRpcRequest("message/stream", input, skill, messageId);
    final HttpRequest httpRequest = buildHttpRequest(request);
    final HttpResponse<Stream<String>> response =
        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
    if (response.statusCode() >= 400) {
      response.body().close();
      throw new IOException("HTTP " + response.statusCode() + " from " + endpoint);
    }
    return new A2AStreamHandle(response.body());
  }

  private A2ATaskResult handleResponse(final HttpResponse<String> response) throws IOException {
    final int status = response.statusCode();
    if (status == 401 || status == 403 || status == 429 || status >= 500) {
      throw new IOException("HTTP " + status + " from " + endpoint);
    }
    if (status >= 400) {
      return A2ATaskResult.protocolError("HTTP " + status);
    }
    return parseTaskResult(MAPPER.readTree(response.body()));
  }

  private ObjectNode buildJsonRpcRequest(
      final String method,
      final Map<String, Object> input,
      final String skill,
      final String messageId) {
    final ObjectNode root = MAPPER.createObjectNode();
    root.put("jsonrpc", "2.0");
    root.put("id", String.valueOf(requestIdCounter.getAndIncrement()));
    root.put("method", method);

    final ObjectNode params = root.putObject("params");
    final ObjectNode message = params.putObject("message");
    message.put("role", "user");
    message.put("messageId", messageId);
    message
        .putArray("parts")
        .addObject()
        .put("type", "text")
        .put("text", MAPPER.valueToTree(input).toString());
    if (skill != null) {
      params.putObject("metadata").put("skill", skill);
    }
    return root;
  }

  private HttpRequest buildHttpRequest(final ObjectNode body) throws IOException {
    final HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
    applyAuth(builder);
    return builder.build();
  }

  private void applyAuth(final HttpRequest.Builder builder) {
    if (auth.type() == A2AAuthConfig.AuthType.NONE) {
      return;
    }
    final String token = ConfigProvider.getConfig().getValue(auth.tokenConfigKey(), String.class);
    switch (auth.type()) {
      case BEARER -> builder.header("Authorization", "Bearer " + token);
      case API_KEY -> builder.header("X-API-Key", token);
      default -> {}
    }
  }

  private A2ATaskResult parseTaskResult(final JsonNode jsonRpcResponse) {
    final JsonNode result = jsonRpcResponse.get("result");
    if (result == null) {
      final JsonNode error = jsonRpcResponse.get("error");
      return A2ATaskResult.protocolError(
          error != null ? error.get("message").asText() : "Unknown JSON-RPC error");
    }
    final String taskId = result.has("id") ? result.get("id").asText() : null;
    final JsonNode status = result.get("status");
    final String state = status.get("state").asText();
    String failureMessage = null;
    if (status.has("message") && status.get("message").has("parts")) {
      failureMessage = status.get("message").get("parts").get(0).get("text").asText();
    }
    final Map<String, Object> output = extractArtifacts(result);
    return new A2ATaskResult(taskId, state, output, failureMessage);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> extractArtifacts(final JsonNode result) {
    if (!result.has("artifacts") || result.get("artifacts").isEmpty()) {
      return Map.of();
    }
    final Map<String, Object> merged = new LinkedHashMap<>();
    for (final JsonNode artifact : result.get("artifacts")) {
      if (artifact.has("parts")) {
        for (final JsonNode part : artifact.get("parts")) {
          if ("text".equals(part.get("type").asText())) {
            try {
              final Map<String, Object> parsed =
                  MAPPER.readValue(part.get("text").asText(), Map.class);
              merged.putAll(parsed);
            } catch (Exception e) {
              merged.put("text", part.get("text").asText());
            }
          }
        }
      }
    }
    return merged;
  }

  @Override
  public void close() {
    // JDK 21 HttpClient has no explicit close
  }

  public void cancelTask(final String taskId) {
    try {
      final ObjectNode root = MAPPER.createObjectNode();
      root.put("jsonrpc", "2.0");
      root.put("id", String.valueOf(requestIdCounter.getAndIncrement()));
      root.put("method", "tasks/cancel");
      root.putObject("params").put("id", taskId);
      final HttpRequest request = buildHttpRequest(root);
      httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    } catch (Exception e) {
      // fire-and-forget — best-effort cancellation only
    }
  }

  public record A2ATaskResult(
      String taskId, String state, Map<String, Object> output, String failureMessage) {
    public static A2ATaskResult protocolError(final String message) {
      return new A2ATaskResult(null, "protocol_error", Map.of(), message);
    }
  }

  public record A2AStreamHandle(Stream<String> eventLines) implements AutoCloseable {
    @Override
    public void close() {
      eventLines.close();
    }
  }
}
