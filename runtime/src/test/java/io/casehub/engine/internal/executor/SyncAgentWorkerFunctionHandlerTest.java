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
package io.casehub.engine.internal.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.api.model.AgentWorkerFunction;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.ai.ModelType;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.WorkerFunctionHandler;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyncAgentWorkerFunctionHandlerTest {

  private WorkerFunctionHandler handler;

  @BeforeEach
  void setUp() {
    WorkerRuntimeFactory mockFactory =
        new WorkerRuntimeFactory(null, null, null, null) {
          @Override
          public io.casehub.api.engine.WorkerRuntime create(UUID caseId) {
            return new DefaultWorkerRuntime(caseId, null, null, null, null);
          }
        };
    handler =
        new SyncAgentWorkerFunctionHandler(
            Executors.newVirtualThreadPerTaskExecutor(), mockFactory);
  }

  @Test
  void supports_sync() {
    var sync = new WorkerFunction.Sync(input -> WorkerResult.of(Map.of()));
    assertThat(handler.supports(sync)).isTrue();
  }

  @Test
  void supports_agent() {
    assertThat(handler.supports(new AgentWorkerFunction(testAgent()))).isTrue();
  }

  @Test
  void does_not_support_unknown() {
    WorkerFunction unknown = new WorkerFunction() {};
    assertThat(handler.supports(unknown)).isFalse();
  }

  @Test
  void executes_sync_function() {
    var sync =
        new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("result", input.get("key"))));
    WorkerResult result =
        handler
            .execute(
                sync,
                Map.of("key", "value"),
                testContext(),
                5000,
                new ExecutionMetadata("w1", "hash1"))
            .await()
            .indefinitely();
    assertThat(result.output()).containsEntry("result", "value");
  }

  @Test
  void executes_agent_function() {
    ChatModelProvider mockProvider =
        new ChatModelProvider() {
          @Override
          public ChatModel get() {
            return new ChatModel() {
              @Override
              public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"agentResult\": \"done\"}"))
                    .build();
              }
            };
          }

          @Override
          public ModelType type() {
            return ModelType.OPENAI;
          }
        };

    Agent agent = Agent.builder().systemPrompt("You are a test agent.").model(mockProvider).build();
    AgentWorkerFunction agentFunction = new AgentWorkerFunction(agent);

    WorkerResult result =
        handler
            .execute(
                agentFunction,
                Map.of("input", "data"),
                testContext(),
                5000,
                new ExecutionMetadata("agent-w", "hash2"))
            .await()
            .indefinitely();

    assertThat(result.output()).containsEntry("agentResult", "done");
  }

  @Test
  void timeout_produces_expired_outcome() {
    WorkerFunction.Sync slowWorker =
        new WorkerFunction.Sync(
            input -> {
              try {
                Thread.sleep(5000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return WorkerResult.of(Map.of("result", "late"));
            });

    WorkerResult result =
        handler
            .execute(
                slowWorker,
                Map.of(),
                testContext(),
                200, // 200ms timeout — worker sleeps 5s
                new ExecutionMetadata("test-worker", "hash-1"))
            .await()
            .atMost(Duration.ofSeconds(10));

    assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Expired.class);
    assertThat(((WorkerOutcome.Expired) result.outcome()).reason()).contains("200ms");
    assertThat(result.output()).isEmpty();
  }

  private WorkerContext testContext() {
    return new WorkerContext(
        "test-worker",
        UUID.randomUUID(),
        null,
        null,
        io.casehub.api.context.PropagationContext.createRoot(),
        null);
  }

  private Agent testAgent() {
    ChatModelProvider mockProvider =
        new ChatModelProvider() {
          @Override
          public ChatModel get() {
            return new ChatModel() {
              @Override
              public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"result\": \"ok\"}"))
                    .build();
              }
            };
          }

          @Override
          public ModelType type() {
            return ModelType.OPENAI;
          }
        };
    return Agent.builder().systemPrompt("Test agent").model(mockProvider).build();
  }
}
