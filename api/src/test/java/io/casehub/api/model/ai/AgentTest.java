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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            .inputSchema(".")
            .outputSchema("{ value: .value }")
            .model(model)
            .build();

    Map<String, Object> result = agent.execute(Map.of("question", "what?"));

    assertEquals(42, result.get("value"));
    assertNull(result.get("extra"));
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
            .inputSchema("{ documentId: .documentId }")
            .outputSchema(".")
            .model(capturingModel)
            .build();

    agent.execute(Map.of("documentId", "doc-1", "extra", "ignored"));

    ChatRequest request = capturedRequest.get();
    assertNotNull(request);

    SystemMessage systemMsg = (SystemMessage) request.messages().get(0);
    assertEquals("System instruction here.", systemMsg.text());

    UserMessage userMsg = (UserMessage) request.messages().get(1);
    Map<String, Object> sentMap = MAPPER.readValue(userMsg.singleText(), MAP_TYPE);
    assertEquals("doc-1", sentMap.get("documentId"));
    assertNull(sentMap.get("extra"));
  }

  @Test
  void builderThrowsWhenSystemPromptMissing() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                Agent.builder()
                    .inputSchema(".")
                    .outputSchema(".")
                    .model(fixedResponseModel("{}"))
                    .build());
    assertTrue(ex.getMessage().contains("systemPrompt"));
  }

  @Test
  void builderThrowsWhenInputSchemaMissing() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                Agent.builder()
                    .systemPrompt("prompt")
                    .outputSchema(".")
                    .model(fixedResponseModel("{}"))
                    .build());
    assertTrue(ex.getMessage().contains("inputSchema"));
  }

  @Test
  void builderThrowsWhenOutputSchemaMissing() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                Agent.builder()
                    .systemPrompt("prompt")
                    .inputSchema(".")
                    .model(fixedResponseModel("{}"))
                    .build());
    assertTrue(ex.getMessage().contains("outputSchema"));
  }

  @Test
  void builderThrowsWhenModelMissing() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                Agent.builder().systemPrompt("prompt").inputSchema(".").outputSchema(".").build());
    assertTrue(ex.getMessage().contains("model"));
  }

  @Test
  void throwsAgentExceptionWhenLlmReturnsInvalidJson() {
    Agent agent =
        Agent.builder()
            .systemPrompt("prompt")
            .inputSchema(".")
            .outputSchema(".")
            .model(fixedResponseModel("this is not json"))
            .build();

    assertThrows(AgentException.class, () -> agent.execute(Map.of("key", "value")));
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
            .inputSchema("{ question: .q, version: .v }")
            .outputSchema(".")
            .model(model)
            .build();

    agent.execute(Map.of("q", "What is Dev Services?", "v", "3.x"));

    UserMessage userMsg = (UserMessage) capturedRequest.get().messages().get(1);
    assertEquals("Answer about Quarkus 3.x: What is Dev Services?", userMsg.singleText());
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
            .inputSchema("{ question: .q }")
            .outputSchema(".")
            .model(model)
            .build();

    agent.execute(Map.of("q", "What is CDI?"));

    UserMessage userMsg = (UserMessage) capturedRequest.get().messages().get(1);
    String userText = userMsg.singleText();
    assertTrue(userText.contains("What is CDI?"));
    assertTrue(userText.startsWith("{"));
  }

  @Test
  void userMessageTemplateMissingPlaceholderThrowsAgentException() {
    Agent agent =
        Agent.builder()
            .systemPrompt("You are helpful")
            .userMessage("Hello {{name}}")
            .inputSchema("{ question: .q }")
            .outputSchema(".")
            .model(fixedResponseModel("{\"answer\": \"ok\"}"))
            .build();

    assertThrows(AgentException.class, () -> agent.execute(Map.of("q", "test")));
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
            .inputSchema(".")
            .outputSchema(".")
            .model(provider)
            .build();

    agent.execute(Map.of("key", "value"));
    assertNotNull(capturedRequest.get());
  }
}
