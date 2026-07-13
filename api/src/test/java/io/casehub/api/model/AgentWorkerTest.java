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
import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.worker.api.Worker;
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
            .inputProjection("{ text: .input }")
            .outputProjection("{ result: .output }")
            .model(fixedResponseProvider("{\"output\": \"processed\"}"))
            .build();

    Worker worker =
        Worker.builder()
            .name("ai-text-processor")
            .capabilityName("text-processing")
            .function(new AgentWorkerFunction(agent))
            .description("AI-powered text processing worker")
            .build();

    assertEquals("ai-text-processor", worker.name());
    assertEquals("AI-powered text processing worker", worker.description());
    assertEquals(1, worker.capabilityNames().size());
    assertEquals("text-processing", worker.capabilityNames().iterator().next());

    assertNotNull(worker.function());
    assertInstanceOf(AgentWorkerFunction.class, worker.function());
  }

  @Test
  void workerExecutesAgentCorrectly() {
    Agent agent =
        Agent.builder()
            .systemPrompt("You translate text to uppercase")
            .inputProjection(".")
            .outputProjection(".")
            .model(fixedResponseProvider("{\"result\": \"HELLO WORLD\"}"))
            .build();

    Worker worker =
        Worker.builder()
            .name("uppercase-transformer")
            .capabilityName("transform")
            .function(new AgentWorkerFunction(agent))
            .build();

    Agent extractedAgent = ((AgentWorkerFunction) worker.function()).agent();
    Map<String, Object> result = extractedAgent.execute(Map.of("text", "hello world")).output();

    assertEquals("HELLO WORLD", result.get("result"));
  }

  @Test
  void agentWorkerSupportsExecutionPolicy() {
    Agent agent =
        Agent.builder()
            .systemPrompt("Test agent")
            .inputProjection(".")
            .outputProjection(".")
            .model(fixedResponseProvider("{}"))
            .build();

    ExecutionPolicy policy =
        new ExecutionPolicy(5000, new RetryPolicy(3, 1000, BackoffStrategy.EXPONENTIAL));

    Worker worker =
        Worker.builder()
            .name("policy-test")
            .capabilityName("test")
            .function(new AgentWorkerFunction(agent))
            .executionPolicy(policy)
            .build();

    assertEquals(5000, worker.executionPolicy().timeoutMs());
    assertEquals(3, worker.executionPolicy().retries().maxAttempts());
    assertEquals(BackoffStrategy.EXPONENTIAL, worker.executionPolicy().retries().backoffStrategy());
  }
}
