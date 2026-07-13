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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private ChatModel fixedResponseModel(String jsonResponse) {
    return new ChatModel() {
      @Override
      public ChatResponse doChat(ChatRequest request) {
        return ChatResponse.builder().aiMessage(AiMessage.from(jsonResponse)).build();
      }
    };
  }

  @Test
  void executesModelAndAppliesOutputSchema() {
    ChatModel model = fixedResponseModel("{\"value\": 42, \"extra\": \"ignored\"}");

    Agent agent =
        Agent.builder()
            .systemPrompt("You are helpful.")
            .inputProjection(".")
            .outputProjection("{ value: .value }")
            .model(model)
            .build();

    Map<String, Object> result = agent.execute(Map.of("question", "what?")).output();

    assertThat(result.get("value")).isEqualTo(42);
    assertThat(result).doesNotContainKey("extra");
  }

  @Test
  void passesSystemPromptAndTransformedInputToModel() throws Exception {
    AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();

    ChatModel capturingModel =
        new ChatModel() {
          @Override
          public ChatResponse doChat(ChatRequest request) {
            capturedRequest.set(request);
            return ChatResponse.builder().aiMessage(AiMessage.from("{\"result\": \"ok\"}")).build();
          }
        };

    Agent agent =
        Agent.builder()
            .systemPrompt("System instruction here.")
            .inputProjection("{ documentId: .documentId }")
            .outputProjection(".")
            .model(capturingModel)
            .build();

    agent.execute(Map.of("documentId", "doc-1", "extra", "ignored"));

    ChatRequest request = capturedRequest.get();
    assertThat(request).isNotNull();

    SystemMessage systemMsg = (SystemMessage) request.messages().get(0);
    assertThat(systemMsg.text()).isEqualTo("System instruction here.");

    UserMessage userMsg = (UserMessage) request.messages().get(1);
    Map<String, Object> sentMap = MAPPER.readValue(userMsg.singleText(), MAP_TYPE);
    assertThat(sentMap.get("documentId")).isEqualTo("doc-1");
    assertThat(sentMap).doesNotContainKey("extra");
  }

  @Test
  void builderThrowsWhenSystemPromptMissing() {
    assertThatThrownBy(
            () ->
                Agent.builder()
                    .inputProjection(".")
                    .outputProjection(".")
                    .model(fixedResponseModel("{}"))
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("systemPrompt");
  }

  // inputSchema and outputSchema are now optional — see AgentBuilderTest for the new API.

  @Test
  void builderThrowsWhenModelMissing() {
    assertThatThrownBy(
            () ->
                Agent.builder()
                    .systemPrompt("prompt")
                    .inputProjection(".")
                    .outputProjection(".")
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("model");
  }

  @Test
  void throwsAgentExceptionWhenLlmReturnsInvalidJson() {
    Agent agent =
        Agent.builder()
            .systemPrompt("prompt")
            .inputProjection(".")
            .outputProjection(".")
            .model(fixedResponseModel("this is not json"))
            .build();

    assertThatThrownBy(() -> agent.execute(Map.of("key", "value")))
        .isInstanceOf(AgentException.class);
  }

  @Test
  void userMessageTemplateIsFilledFromJqResult() {
    AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse doChat(ChatRequest request) {
            capturedRequest.set(request);
            return ChatResponse.builder().aiMessage(AiMessage.from("{\"answer\": \"ok\"}")).build();
          }
        };

    Agent agent =
        Agent.builder()
            .systemPrompt("You are helpful")
            .userMessage("Answer about Quarkus {{version}}: {{question}}")
            .inputProjection("{ question: .q, version: .v }")
            .outputProjection(".")
            .model(model)
            .build();

    agent.execute(Map.of("q", "What is Dev Services?", "v", "3.x"));

    UserMessage userMsg = (UserMessage) capturedRequest.get().messages().get(1);
    assertThat(userMsg.singleText()).isEqualTo("Answer about Quarkus 3.x: What is Dev Services?");
  }

  @Test
  void withoutUserMessageTemplateUsesJsonFallback() {
    AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse doChat(ChatRequest request) {
            capturedRequest.set(request);
            return ChatResponse.builder().aiMessage(AiMessage.from("{\"answer\": \"ok\"}")).build();
          }
        };

    Agent agent =
        Agent.builder()
            .systemPrompt("You are helpful")
            .inputProjection("{ question: .q }")
            .outputProjection(".")
            .model(model)
            .build();

    agent.execute(Map.of("q", "What is CDI?"));

    UserMessage userMsg = (UserMessage) capturedRequest.get().messages().get(1);
    String userText = userMsg.singleText();
    assertThat(userText).contains("What is CDI?").startsWith("{");
  }

  @Test
  void userMessageTemplateMissingPlaceholderThrowsAgentException() {
    Agent agent =
        Agent.builder()
            .systemPrompt("You are helpful")
            .userMessage("Hello {{name}}")
            .inputProjection("{ question: .q }")
            .outputProjection(".")
            .model(fixedResponseModel("{\"answer\": \"ok\"}"))
            .build();

    assertThatThrownBy(() -> agent.execute(Map.of("q", "test"))).isInstanceOf(AgentException.class);
  }

  @Test
  void acceptsCustomChatModelProvider() {
    AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
    ChatModel mockModel =
        new ChatModel() {
          @Override
          public ChatResponse doChat(ChatRequest request) {
            capturedRequest.set(request);
            return ChatResponse.builder().aiMessage(AiMessage.from("{\"result\": \"ok\"}")).build();
          }
        };

    ChatModelProvider provider =
        new ChatModelProvider() {
          @Override
          public ModelType type() {
            return ModelType.OPENAI;
          }

          @Override
          public ChatModel get() {
            return mockModel;
          }
        };

    Agent agent =
        Agent.builder()
            .systemPrompt("test")
            .inputProjection(".")
            .outputProjection(".")
            .model(provider)
            .build();

    agent.execute(Map.of("key", "value"));
    assertThat(capturedRequest.get()).isNotNull();
  }

  @Test
  void executeThrowsWhenInputTransformerFails() {
    Agent agent =
        Agent.builder()
            .systemPrompt("test")
            .inputTransformer(
                node -> {
                  throw new RuntimeException("input transform failed");
                })
            .outputProjection(".")
            .model(fixedResponseModel("{\"result\": \"ok\"}"))
            .build();

    assertThatThrownBy(() -> agent.execute(Map.of("key", "value")))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("input transform failed");
  }

  @Test
  void executeThrowsWhenOutputTransformerFails() {
    Agent agent =
        Agent.builder()
            .systemPrompt("test")
            .inputProjection(".")
            .outputTransformer(
                node -> {
                  throw new RuntimeException("output transform failed");
                })
            .model(fixedResponseModel("{\"result\": \"ok\"}"))
            .build();

    assertThatThrownBy(() -> agent.execute(Map.of("key", "value")))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("output transform failed");
  }

  @Test
  void executeThrowsWhenLlmReturnsEmptyString() {
    Agent agent =
        Agent.builder()
            .systemPrompt("test")
            .inputProjection(".")
            .outputProjection(".")
            .model(fixedResponseModel(""))
            .build();

    assertThatThrownBy(() -> agent.execute(Map.of("key", "value")))
        .isInstanceOf(AgentException.class);
  }
}
