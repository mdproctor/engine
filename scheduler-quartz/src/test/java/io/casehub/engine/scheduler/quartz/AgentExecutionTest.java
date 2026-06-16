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
package io.casehub.engine.scheduler.quartz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.api.model.Capability;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerResult;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.ai.ModelType;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentExecutionTest {

  private QuartzWorkerExecutionJob job;

  @BeforeEach
  void setUp() {
    job = new QuartzWorkerExecutionJob();
  }

  @Test
  void agentMethodExecutesSuccessfully() throws Exception {
    ChatModelProvider mockProvider = createMockProvider("{\"sentiment\": \"POSITIVE\"}");

    Agent agent =
        Agent.builder()
            .systemPrompt("Analyze sentiment")
            .inputSchema("{ text: .text }")
            .outputSchema("{ sentiment: .sentiment }")
            .model(mockProvider)
            .build();

    Map<String, Object> input = Map.of("text", "I love this product!");
    WorkerContext context = new WorkerContext(null, null, null, null, null, null);
    int timeout = 5000;

    WorkerResult result = invokeAgentMethod(agent, input, context, timeout);

    assertNotNull(result);
    assertEquals("POSITIVE", result.output().get("sentiment"));
  }

  @Test
  void agentMethodHandlesTimeout() {
    ChatModelProvider slowProvider =
        new ChatModelProvider() {
          @Override
          public ModelType type() {
            return ModelType.OPENAI;
          }

          @Override
          public ChatModel get() {
            return new ChatModel() {
              @Override
              public ChatResponse doChat(ChatRequest request) {
                try {
                  Thread.sleep(10000); // 10 seconds
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"result\": \"ok\"}"))
                    .build();
              }
            };
          }
        };

    Agent agent =
        Agent.builder()
            .systemPrompt("Test")
            .inputSchema(".")
            .outputSchema(".")
            .model(slowProvider)
            .build();

    Map<String, Object> input = Map.of("key", "value");
    WorkerContext context = new WorkerContext(null, null, null, null, null, null);
    int timeout = 100; // Very short timeout

    // CompletableFuture.get(timeout) throws TimeoutException directly, not wrapped in
    // ExecutionException
    assertThrows(TimeoutException.class, () -> invokeAgentMethod(agent, input, context, timeout));
  }

  @Test
  void agentWorkerIntegrationWithWorkerClass() throws Exception {
    ChatModelProvider mockProvider = createMockProvider("{\"classification\": \"technical\"}");

    Agent classifier =
        Agent.builder()
            .systemPrompt("Classify documents")
            .inputSchema("{ content: .content }")
            .outputSchema("{ classification: .classification }")
            .model(mockProvider)
            .build();

    Capability docClassification =
        new Capability("classify", "{ content: .document }", "{ type: .classification }");

    Worker worker =
        Worker.builder()
            .name("doc-classifier")
            .capabilities(docClassification)
            .function(classifier)
            .build();

    assertEquals("doc-classifier", worker.getName());
    assertNotNull(worker.getFunction());
    assertInstanceOf(io.casehub.api.model.WorkerFunction.AgentExec.class, worker.getFunction());
  }

  // Helper methods

  private ChatModelProvider createMockProvider(String response) {
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
            return ChatResponse.builder().aiMessage(AiMessage.from(response)).build();
          }
        };
      }
    };
  }

  private WorkerResult invokeAgentMethod(
      Agent agent, Map<String, Object> input, WorkerContext context, int timeout) throws Exception {
    Method agentMethod =
        QuartzWorkerExecutionJob.class.getDeclaredMethod(
            "agent", Agent.class, Map.class, WorkerContext.class, int.class);
    agentMethod.setAccessible(true);

    try {
      WorkerResult result = (WorkerResult) agentMethod.invoke(job, agent, input, context, timeout);
      return result;
    } catch (java.lang.reflect.InvocationTargetException e) {
      // Re-throw the original exception
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw e;
    }
  }
}
