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
package io.casehub.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.ai.ModelType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentWorkerTest {

  private ChatModelProvider fixedResponseProvider(String jsonResponse) {
    return new ChatModelProvider() {
      @Override
      public ModelType type() {
        return ModelType.OPENAI;
      }

      @Override
      public ChatModel get() {
        return new ChatModel() {
          @Override
          public ChatResponse doChat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(jsonResponse)).build();
          }
        };
      }
    };
  }

  @Test
  void workerCanBeCreatedWithAgent() {
    Agent agent =
        Agent.builder()
            .systemPrompt("You are a helpful assistant")
            .inputSchema("{ text: .input }")
            .outputSchema("{ result: .output }")
            .model(fixedResponseProvider("{\"output\": \"processed\"}"))
            .build();

    Capability textProcessing =
        new Capability("text-processing", "{ input: .text }", "{ text: .result }");

    Worker worker =
        Worker.builder()
            .name("ai-text-processor")
            .capabilities(textProcessing)
            .function(agent)
            .description("AI-powered text processing worker")
            .build();

    assertEquals("ai-text-processor", worker.getName());
    assertEquals("AI-powered text processing worker", worker.getDescription());
    assertEquals(1, worker.getCapabilities().size());
    assertEquals("text-processing", worker.getCapabilities().get(0).getName());

    assertNotNull(worker.getFunction());
    assertInstanceOf(WorkerFunction.AgentExec.class, worker.getFunction());
  }

  @Test
  void workerExecutesAgentCorrectly() {
    Agent agent =
        Agent.builder()
            .systemPrompt("You translate text to uppercase")
            .inputSchema(".")
            .outputSchema(".")
            .model(fixedResponseProvider("{\"result\": \"HELLO WORLD\"}"))
            .build();

    Capability textTransform = new Capability("transform", ".", ".");

    Worker worker =
        Worker.builder()
            .name("uppercase-transformer")
            .capabilities(textTransform)
            .function(agent)
            .build();

    Agent extractedAgent = ((WorkerFunction.AgentExec) worker.getFunction()).agent();
    Map<String, Object> result = extractedAgent.execute(Map.of("text", "hello world")).output();

    assertEquals("HELLO WORLD", result.get("result"));
  }

  @Test
  void agentWorkerSupportsExecutionPolicy() {
    Agent agent =
        Agent.builder()
            .systemPrompt("Test agent")
            .inputSchema(".")
            .outputSchema(".")
            .model(fixedResponseProvider("{}"))
            .build();

    ExecutionPolicy policy =
        new ExecutionPolicy(5000, new RetryPolicy(3, 1000, BackoffStrategy.EXPONENTIAL));

    Worker worker =
        Worker.builder()
            .name("policy-test")
            .capabilities(List.of(new Capability("test", ".", ".")))
            .function(agent)
            .executionPolicy(policy)
            .build();

    assertEquals(5000, worker.getExecutionPolicy().timeoutMs());
    assertEquals(3, worker.getExecutionPolicy().retries().maxAttempts());
    assertEquals(
        BackoffStrategy.EXPONENTIAL, worker.getExecutionPolicy().retries().backoffStrategy());
  }
}
