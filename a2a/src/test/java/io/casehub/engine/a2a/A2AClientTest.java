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

import java.io.IOException;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class A2AClientTest {

  private MockWebServer server;
  private A2AClient client;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    client = new A2AClient(server.url("/").toString(), A2AAuthConfig.NONE);
  }

  @AfterEach
  void tearDown() throws Exception {
    client.close();
    server.shutdown();
  }

  @Test
  void sendReturnsCompletedResult() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(
                """
                {"jsonrpc":"2.0","id":"1","result":{"id":"task-1","status":{"state":"completed"},"artifacts":[{"parts":[{"type":"text","text":"{\\"answer\\":42}"}]}]}}
                """)
            .addHeader("Content-Type", "application/json"));

    A2AClient.A2ATaskResult result = client.send(Map.of("question", "meaning"), null, "msg-1");

    assertThat(result.taskId()).isEqualTo("task-1");
    assertThat(result.state()).isEqualTo("completed");
    assertThat(result.output()).containsEntry("answer", 42);
  }

  @Test
  void sendReturnsFailed() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(
                """
                {"jsonrpc":"2.0","id":"1","result":{"id":"task-2","status":{"state":"failed","message":{"parts":[{"type":"text","text":"Something broke"}]}}}}
                """)
            .addHeader("Content-Type", "application/json"));

    A2AClient.A2ATaskResult result = client.send(Map.of(), null, "msg-2");

    assertThat(result.state()).isEqualTo("failed");
    assertThat(result.failureMessage()).isEqualTo("Something broke");
  }

  @Test
  void sendReturnsInputRequired() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(
                """
                {"jsonrpc":"2.0","id":"1","result":{"id":"task-3","status":{"state":"input_required"}}}
                """)
            .addHeader("Content-Type", "application/json"));

    A2AClient.A2ATaskResult result = client.send(Map.of(), null, "msg-3");

    assertThat(result.state()).isEqualTo("input_required");
  }

  @Test
  void sendWithSkillIncludesMetadata() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(
                """
                {"jsonrpc":"2.0","id":"1","result":{"id":"t","status":{"state":"completed"},"artifacts":[]}}
                """)
            .addHeader("Content-Type", "application/json"));

    client.send(Map.of(), "anomaly-detection", "msg-4");

    var request = server.takeRequest();
    String body = request.getBody().readUtf8();
    assertThat(body).contains("\"skill\"");
    assertThat(body).contains("anomaly-detection");
  }

  @Test
  void sendWithBearerAuthIncludesHeader() throws Exception {
    client.close();
    client =
        new A2AClient(
            server.url("/").toString(),
            new A2AAuthConfig(A2AAuthConfig.AuthType.BEARER, "test.a2a.token"));

    server.enqueue(
        new MockResponse()
            .setBody(
                """
                {"jsonrpc":"2.0","id":"1","result":{"id":"t","status":{"state":"completed"},"artifacts":[]}}
                """)
            .addHeader("Content-Type", "application/json"));

    client.send(Map.of(), null, "msg-5");

    var request = server.takeRequest();
    assertThat(request.getHeaders().get("Authorization")).startsWith("Bearer ");
  }

  @Test
  void sendWithApiKeyAuthIncludesHeader() throws Exception {
    client.close();
    client =
        new A2AClient(
            server.url("/").toString(),
            new A2AAuthConfig(A2AAuthConfig.AuthType.API_KEY, "test.a2a.apikey"));

    server.enqueue(
        new MockResponse()
            .setBody(
                """
                {"jsonrpc":"2.0","id":"1","result":{"id":"t","status":{"state":"completed"},"artifacts":[]}}
                """)
            .addHeader("Content-Type", "application/json"));

    client.send(Map.of(), null, "msg-6");

    var request = server.takeRequest();
    assertThat(request.getHeaders().get("X-API-Key")).isNotNull();
  }

  @Test
  void sendThrowsOnHttp5xx() {
    server.enqueue(new MockResponse().setResponseCode(503));

    assertThatThrownBy(() -> client.send(Map.of(), null, "msg-7"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("503");
  }

  @Test
  void sendThrowsOnHttp401() {
    server.enqueue(new MockResponse().setResponseCode(401));

    assertThatThrownBy(() -> client.send(Map.of(), null, "msg-8"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("401");
  }

  @Test
  void sendThrowsOnHttp429() {
    server.enqueue(new MockResponse().setResponseCode(429));

    assertThatThrownBy(() -> client.send(Map.of(), null, "msg-9"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("429");
  }

  @Test
  void sendReturnsProtocolErrorOnHttp4xx() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(404));

    A2AClient.A2ATaskResult result = client.send(Map.of(), null, "msg-10");

    assertThat(result.state()).isEqualTo("protocol_error");
    assertThat(result.failureMessage()).contains("404");
  }

  @Test
  void sendReturnsProtocolErrorOnJsonRpcError() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(
                """
                {"jsonrpc":"2.0","id":"1","error":{"code":-32600,"message":"Invalid Request"}}
                """)
            .addHeader("Content-Type", "application/json"));

    A2AClient.A2ATaskResult result = client.send(Map.of(), null, "msg-11");

    assertThat(result.state()).isEqualTo("protocol_error");
    assertThat(result.failureMessage()).isEqualTo("Invalid Request");
  }

  @Test
  void sendSetsCorrectJsonRpcMethod() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(
                """
                {"jsonrpc":"2.0","id":"1","result":{"id":"t","status":{"state":"completed"},"artifacts":[]}}
                """)
            .addHeader("Content-Type", "application/json"));

    client.send(Map.of("key", "value"), null, "msg-12");

    var request = server.takeRequest();
    String body = request.getBody().readUtf8();
    assertThat(body).contains("\"method\":\"message/send\"");
    assertThat(body).contains("\"jsonrpc\":\"2.0\"");
    assertThat(body).contains("\"messageId\":\"msg-12\"");
  }

  @Test
  void sendHandlesEmptyArtifacts() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(
                """
                {"jsonrpc":"2.0","id":"1","result":{"id":"t","status":{"state":"completed"},"artifacts":[]}}
                """)
            .addHeader("Content-Type", "application/json"));

    A2AClient.A2ATaskResult result = client.send(Map.of(), null, "msg-13");

    assertThat(result.state()).isEqualTo("completed");
    assertThat(result.output()).isEmpty();
  }

  @Test
  void sendHandlesNonJsonTextArtifact() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(
                """
                {"jsonrpc":"2.0","id":"1","result":{"id":"t","status":{"state":"completed"},"artifacts":[{"parts":[{"type":"text","text":"plain text response"}]}]}}
                """)
            .addHeader("Content-Type", "application/json"));

    A2AClient.A2ATaskResult result = client.send(Map.of(), null, "msg-14");

    assertThat(result.output()).containsEntry("text", "plain text response");
  }
}
