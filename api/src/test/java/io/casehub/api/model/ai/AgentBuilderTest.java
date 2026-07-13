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
package io.casehub.api.model.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class AgentBuilderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Minimal ChatModel stub — returns the supplied JSON string for all requests. */
  private static ChatModel stubModel(final String responseJson) {
    return new ChatModel() {
      @Override
      public ChatResponse doChat(final ChatRequest request) {
        return ChatResponse.builder()
            .aiMessage(AiMessage.from(responseJson))
            .finishReason(FinishReason.STOP)
            .build();
      }
    };
  }

  // ── engine#316: custom transformer support ─────────────────────────────

  @Test
  void agentBuilder_accepts_custom_input_and_output_transformer_without_schema_strings() {
    // AgentBuilder should build without requiring inputSchema/outputSchema
    // when transformer functions are provided directly.
    // Currently fails: build() throws IllegalStateException("inputSchema is required").
    assertThatNoException()
        .isThrownBy(
            () ->
                Agent.builder()
                    .systemPrompt("You are a helpful assistant.")
                    .inputTransformer(UnaryOperator.identity())
                    .outputTransformer(UnaryOperator.identity())
                    .model(stubModel("{}"))
                    .build());
  }

  @Test
  void agent_applies_custom_input_transformer_during_execute() {
    // The custom inputTransformer must be called with the input JsonNode.
    final AtomicBoolean transformerCalled = new AtomicBoolean(false);
    final UnaryOperator<JsonNode> recordingTransformer =
        node -> {
          transformerCalled.set(true);
          return node;
        };

    final Agent agent =
        Agent.builder()
            .systemPrompt("You are a helpful assistant.")
            .inputTransformer(recordingTransformer)
            .outputTransformer(UnaryOperator.identity())
            .model(stubModel("{}"))
            .build();

    agent.execute(Map.of("key", "value"));

    assertThat(transformerCalled.get())
        .as("custom inputTransformer must be called during execute()")
        .isTrue();
  }

  @Test
  void agent_applies_custom_output_transformer_during_execute() {
    // The custom outputTransformer must be called on the LLM response.
    final AtomicBoolean transformerCalled = new AtomicBoolean(false);
    final UnaryOperator<JsonNode> recordingTransformer =
        node -> {
          transformerCalled.set(true);
          return node;
        };

    final Agent agent =
        Agent.builder()
            .systemPrompt("You are a helpful assistant.")
            .inputTransformer(UnaryOperator.identity())
            .outputTransformer(recordingTransformer)
            .model(stubModel("{\"result\": \"ok\"}"))
            .build();

    agent.execute(Map.of("key", "value"));

    assertThat(transformerCalled.get())
        .as("custom outputTransformer must be called on LLM response during execute()")
        .isTrue();
  }

  @Test
  void agent_defaults_to_identity_transformer_when_no_schema_or_transformer_set() {
    // When neither inputSchema nor inputTransformer is set, identity is the default —
    // the full input passes through unchanged.
    final JsonNode input = MAPPER.createObjectNode().put("status", "pending");

    final Agent agent =
        Agent.builder()
            .systemPrompt("You are a helpful assistant.")
            .model(stubModel("{\"answer\": \"yes\"}"))
            .build();

    // Should not throw — just runs with identity transformers
    final Map<String, Object> result = agent.execute(Map.of("status", "pending")).output();
    assertThat(result).containsKey("answer");
  }

  // ── Conflict guard ────────────────────────────────────────────────────────

  @Test
  void agentBuilder_throws_when_both_inputSchema_and_inputTransformer_set() {
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                Agent.builder()
                    .systemPrompt("prompt")
                    .inputProjection(".")
                    .inputTransformer(UnaryOperator.identity())
                    .outputTransformer(UnaryOperator.identity())
                    .model(stubModel("{}"))
                    .build())
        .withMessageContaining("inputSchema")
        .withMessageContaining("inputTransformer");
  }

  @Test
  void agentBuilder_throws_when_both_outputSchema_and_outputTransformer_set() {
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                Agent.builder()
                    .systemPrompt("prompt")
                    .inputTransformer(UnaryOperator.identity())
                    .outputProjection(".")
                    .outputTransformer(UnaryOperator.identity())
                    .model(stubModel("{}"))
                    .build())
        .withMessageContaining("outputSchema")
        .withMessageContaining("outputTransformer");
  }

  // ── Existing schema string path still works ─────────────────────────────

  @Test
  void agentBuilder_still_accepts_schema_strings_for_backwards_compatibility() {
    assertThatNoException()
        .isThrownBy(
            () ->
                Agent.builder()
                    .systemPrompt("You are a helpful assistant.")
                    .inputProjection("{ status: .status }")
                    .outputProjection(".")
                    .model(stubModel("{\"answer\": \"yes\"}"))
                    .build());
  }

  // ── responseSchema support ─────────────────────────────────────────────

  @Test
  void agentBuilder_sets_response_schema_from_json_schema() {
    final JsonSchema schema =
        JsonSchema.builder()
            .name("TestResponse")
            .rootElement(
                JsonObjectSchema.builder()
                    .addProperty("result", new JsonStringSchema())
                    .required("result")
                    .build())
            .build();

    final AtomicReference<ChatRequest> captured = new AtomicReference<>();
    final ChatModel capturingModel =
        new ChatModel() {
          @Override
          public ChatResponse doChat(final ChatRequest request) {
            captured.set(request);
            return ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"result\": \"ok\"}"))
                .finishReason(FinishReason.STOP)
                .build();
          }
        };

    final Agent agent =
        Agent.builder()
            .systemPrompt("You are a helpful assistant.")
            .responseSchema(schema)
            .model(capturingModel)
            .build();

    agent.execute(Map.of("key", "value"));

    assertThat(captured.get().responseFormat()).isNotNull();
    assertThat(captured.get().responseFormat().type()).isEqualTo(ResponseFormatType.JSON);
    assertThat(captured.get().responseFormat().jsonSchema()).isEqualTo(schema);
  }

  @Test
  void agentBuilder_sets_response_schema_from_class() {
    final Agent agent =
        Agent.builder()
            .systemPrompt("You are a helpful assistant.")
            .responseSchema(TestRecord.class)
            .model(stubModel("{\"value\": \"hello\"}"))
            .build();

    assertThat(agent).isNotNull();
  }

  static class TestRecord {
    public String value;
  }
}
