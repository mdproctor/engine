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
package io.casehub.engine.planning.decomposition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.api.model.ai.AgentException;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.ai.ModelType;
import io.casehub.engine.plan.TaskNode;
import io.casehub.worker.api.Capability;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmDecompositionStrategyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void producesSequentialDagPlanFromLlmResponse() {
    var strategy = buildWithMockChatModel(sequentialResponse());
    var context =
        new GoalDecompositionContext(
            MAPPER.createObjectNode(),
            0,
            List.of(
                new Capability("data-gathering", "", "", null),
                new Capability("analysis", "", "", null),
                new Capability("reporting", "", "", null)));
    var task =
        new TaskNode.CompoundTask<JsonNode>(
            "comprehensive-analysis", "comprehensive-analysis", List.of());

    var plan = strategy.decompose(task, context);

    assertThat(plan.nodes()).hasSize(3);
    var sorted = plan.topologicalSort();
    assertThat(((GoalStep) sorted.get(0).task()).capabilityName()).isEqualTo("data-gathering");
    assertThat(((GoalStep) sorted.get(1).task()).capabilityName()).isEqualTo("analysis");
    assertThat(((GoalStep) sorted.get(2).task()).capabilityName()).isEqualTo("reporting");
  }

  @Test
  void idIsLlm() {
    var strategy = buildWithMockChatModel(sequentialResponse());
    assertThat(strategy.id()).isEqualTo("llm");
  }

  @Test
  void failsWhenChatModelProviderAbsent() {
    var strategy = new LlmDecompositionStrategy();
    setField(strategy, "chatModelProviders", unsatisfiedInstance());
    var context = new GoalDecompositionContext(MAPPER.createObjectNode(), 0, List.of());
    var task = new TaskNode.CompoundTask<JsonNode>("goal-1", "goal-1", List.of());

    assertThatThrownBy(() -> strategy.decompose(task, context))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void failsOnEmptySteps() {
    var strategy = buildWithMockChatModel("{\"steps\": []}");
    var context = new GoalDecompositionContext(MAPPER.createObjectNode(), 0, List.of());
    var task = new TaskNode.CompoundTask<JsonNode>("goal-1", "goal-1", List.of());

    assertThatThrownBy(() -> strategy.decompose(task, context))
        .isInstanceOf(AgentException.class)
        .hasMessageContaining("no steps");
  }

  @Test
  void failsOnMissingStepsField() {
    var strategy = buildWithMockChatModel("{\"result\": \"ok\"}");
    var context = new GoalDecompositionContext(MAPPER.createObjectNode(), 0, List.of());
    var task = new TaskNode.CompoundTask<JsonNode>("goal-1", "goal-1", List.of());

    assertThatThrownBy(() -> strategy.decompose(task, context)).isInstanceOf(AgentException.class);
  }

  @Test
  void includesConstraintsInPromptWhenPresent() {
    var capturedPrompt = new java.util.concurrent.atomic.AtomicReference<String>();

    ChatModel capturingModel =
        new ChatModel() {
          @Override
          public ChatResponse doChat(ChatRequest request) {
            var messages = request.messages();
            for (var msg : messages) {
              if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
                capturedPrompt.set(um.singleText());
              }
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(sequentialResponse())).build();
          }
        };

    ChatModelProvider provider =
        new ChatModelProvider() {
          @Override
          public ModelType type() {
            return ModelType.ANTHROPIC;
          }

          @Override
          public ChatModel get() {
            return capturingModel;
          }
        };

    var strategy = new LlmDecompositionStrategy();
    setField(strategy, "chatModelProviders", satisfiedInstance(provider));

    var constraints =
        io.casehub.engine.plan.PlanningConstraints.of(java.time.Duration.ofMinutes(30), 3);
    var context =
        new GoalDecompositionContext(
            MAPPER.createObjectNode(),
            0,
            List.of(new Capability("analysis", "", "", null)),
            constraints);
    var task = new TaskNode.CompoundTask<JsonNode>("research", "research", List.of());

    strategy.decompose(task, context);

    assertThat(capturedPrompt.get()).contains("30 minutes").contains("3");
  }

  @Test
  void includesCostBudgetsInPromptWhenPresent() {
    var capturedPrompt = new java.util.concurrent.atomic.AtomicReference<String>();

    ChatModel capturingModel =
        new ChatModel() {
          @Override
          public ChatResponse doChat(ChatRequest request) {
            for (var msg : request.messages()) {
              if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
                capturedPrompt.set(um.singleText());
              }
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(sequentialResponse())).build();
          }
        };

    ChatModelProvider provider =
        new ChatModelProvider() {
          @Override
          public ModelType type() {
            return ModelType.ANTHROPIC;
          }

          @Override
          public ChatModel get() {
            return capturingModel;
          }
        };

    var strategy = new LlmDecompositionStrategy();
    setField(strategy, "chatModelProviders", satisfiedInstance(provider));

    var constraints =
        new io.casehub.engine.plan.PlanningConstraints(
            null, null, java.util.Map.of(), java.util.Map.of("tokens", 5000, "apiCalls", 10));
    var context =
        new GoalDecompositionContext(
            MAPPER.createObjectNode(),
            0,
            List.of(new Capability("analysis", "", "", null)),
            constraints);
    var task = new TaskNode.CompoundTask<JsonNode>("research", "research", List.of());

    strategy.decompose(task, context);

    assertThat(capturedPrompt.get()).contains("5000").contains("apiCalls");
  }

  @Test
  void includesWeightsInPromptWhenPresent() {
    var capturedPrompt = new java.util.concurrent.atomic.AtomicReference<String>();

    ChatModel capturingModel =
        new ChatModel() {
          @Override
          public ChatResponse doChat(ChatRequest request) {
            for (var msg : request.messages()) {
              if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
                capturedPrompt.set(um.singleText());
              }
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(sequentialResponse())).build();
          }
        };

    ChatModelProvider provider =
        new ChatModelProvider() {
          @Override
          public ModelType type() {
            return ModelType.ANTHROPIC;
          }

          @Override
          public ChatModel get() {
            return capturingModel;
          }
        };

    var strategy = new LlmDecompositionStrategy();
    setField(strategy, "chatModelProviders", satisfiedInstance(provider));

    var constraints =
        new io.casehub.engine.plan.PlanningConstraints(
            null, null, java.util.Map.of("speed", 0.8, "quality", 0.2), java.util.Map.of());
    var context =
        new GoalDecompositionContext(
            MAPPER.createObjectNode(),
            0,
            List.of(new Capability("analysis", "", "", null)),
            constraints);
    var task = new TaskNode.CompoundTask<JsonNode>("research", "research", List.of());

    strategy.decompose(task, context);

    assertThat(capturedPrompt.get()).contains("speed").contains("0.8").contains("quality");
  }

  @Test
  void rendersCostBudgetsOnlyWithNoTimeBudgetOrResourceLimit() {
    var capturedPrompt = new java.util.concurrent.atomic.AtomicReference<String>();

    ChatModel capturingModel =
        new ChatModel() {
          @Override
          public ChatResponse doChat(ChatRequest request) {
            for (var msg : request.messages()) {
              if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
                capturedPrompt.set(um.singleText());
              }
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(sequentialResponse())).build();
          }
        };

    ChatModelProvider provider =
        new ChatModelProvider() {
          @Override
          public ModelType type() {
            return ModelType.ANTHROPIC;
          }

          @Override
          public ChatModel get() {
            return capturingModel;
          }
        };

    var strategy = new LlmDecompositionStrategy();
    setField(strategy, "chatModelProviders", satisfiedInstance(provider));

    var constraints =
        new io.casehub.engine.plan.PlanningConstraints(
            null, null, java.util.Map.of(), java.util.Map.of("tokens", 3000));
    var context =
        new GoalDecompositionContext(
            MAPPER.createObjectNode(),
            0,
            List.of(new Capability("analysis", "", "", null)),
            constraints);
    var task = new TaskNode.CompoundTask<JsonNode>("research", "research", List.of());

    strategy.decompose(task, context);

    assertThat(capturedPrompt.get()).contains("3000").contains("Constraints:");
  }

  @Test
  void omitsConstraintTextWhenUnconstrained() {
    var capturedPrompt = new java.util.concurrent.atomic.AtomicReference<String>();

    ChatModel capturingModel =
        new ChatModel() {
          @Override
          public ChatResponse doChat(ChatRequest request) {
            for (var msg : request.messages()) {
              if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
                capturedPrompt.set(um.singleText());
              }
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(sequentialResponse())).build();
          }
        };

    ChatModelProvider provider =
        new ChatModelProvider() {
          @Override
          public ModelType type() {
            return ModelType.ANTHROPIC;
          }

          @Override
          public ChatModel get() {
            return capturingModel;
          }
        };

    var strategy = new LlmDecompositionStrategy();
    setField(strategy, "chatModelProviders", satisfiedInstance(provider));

    var context =
        new GoalDecompositionContext(
            MAPPER.createObjectNode(), 0, List.of(new Capability("analysis", "", "", null)));
    var task = new TaskNode.CompoundTask<JsonNode>("research", "research", List.of());

    strategy.decompose(task, context);

    assertThat(capturedPrompt.get()).doesNotContain("Constraints:");
  }

  @Test
  void replanIncludesFailureContextInPrompt() {
    var capturedPrompt = new java.util.concurrent.atomic.AtomicReference<String>();

    ChatModel capturingModel =
        new ChatModel() {
          @Override
          public ChatResponse doChat(ChatRequest request) {
            var messages = request.messages();
            for (var msg : messages) {
              if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
                capturedPrompt.set(um.singleText());
              }
            }
            return ChatResponse.builder()
                .aiMessage(
                    AiMessage.from(
                        "{\"steps\": [{\"id\": \"r1\", \"description\": \"recovery step\","
                            + " \"capabilityName\": \"analysis\"}]}"))
                .build();
          }
        };

    ChatModelProvider provider =
        new ChatModelProvider() {
          @Override
          public ModelType type() {
            return ModelType.ANTHROPIC;
          }

          @Override
          public ChatModel get() {
            return capturingModel;
          }
        };

    var strategy = new LlmDecompositionStrategy();
    setField(strategy, "chatModelProviders", satisfiedInstance(provider));

    var completed =
        List.of(
            new io.casehub.engine.plan.ReplanContext.CompletedStep(
                "s1", java.util.Map.of("result", "ok"), java.time.Duration.ofSeconds(2)));
    var failed =
        new io.casehub.engine.plan.ReplanContext.FailedStep("s2", "service unavailable", null, 3);
    var replanCtx = new io.casehub.engine.plan.ReplanContext<JsonNode>(completed, failed, null, 0);

    var task = new TaskNode.CompoundTask<JsonNode>("ct-1", "analyse-data", List.of());
    var context =
        new GoalDecompositionContext(
            MAPPER.createObjectNode(),
            0,
            List.of(new Capability("analysis", "Analyse data", "", null)));

    var result = strategy.replan(task, context, replanCtx);

    assertThat(result.nodes()).hasSize(1);
    var prompt = capturedPrompt.get();
    assertThat(prompt).contains("analyse-data");
    assertThat(prompt).contains("service unavailable");
    assertThat(prompt).contains("Completed steps");
    assertThat(prompt).contains("3 retries");
  }

  @Test
  void replanFailsWhenChatModelAbsent() {
    var strategy = new LlmDecompositionStrategy();
    setField(strategy, "chatModelProviders", unsatisfiedInstance());

    var failed = new io.casehub.engine.plan.ReplanContext.FailedStep("s1", "err", null, 0);
    var replanCtx = new io.casehub.engine.plan.ReplanContext<JsonNode>(List.of(), failed, null, 0);
    var task = new TaskNode.CompoundTask<JsonNode>("ct-1", "goal-1", List.of());
    var context = new GoalDecompositionContext(MAPPER.createObjectNode(), 0, List.of());

    assertThatThrownBy(() -> strategy.replan(task, context, replanCtx))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void replanFailsOnEmptySteps() {
    var strategy = buildWithMockChatModel("{\"steps\": []}");

    var failed = new io.casehub.engine.plan.ReplanContext.FailedStep("s1", "err", null, 0);
    var replanCtx = new io.casehub.engine.plan.ReplanContext<JsonNode>(List.of(), failed, null, 0);
    var task = new TaskNode.CompoundTask<JsonNode>("ct-1", "goal-1", List.of());
    var context = new GoalDecompositionContext(MAPPER.createObjectNode(), 0, List.of());

    assertThatThrownBy(() -> strategy.replan(task, context, replanCtx))
        .isInstanceOf(AgentException.class)
        .hasMessageContaining("no steps");
  }

  private static String sequentialResponse() {
    return "{\"steps\": ["
        + "{\"id\": \"s1\", \"description\": \"Gather data\", \"capabilityName\": \"data-gathering\"},"
        + "{\"id\": \"s2\", \"description\": \"Analyse\", \"capabilityName\": \"analysis\", \"dependsOn\": [\"s1\"]},"
        + "{\"id\": \"s3\", \"description\": \"Report\", \"capabilityName\": \"reporting\", \"dependsOn\": [\"s2\"]}"
        + "]}";
  }

  private static LlmDecompositionStrategy buildWithMockChatModel(String cannedResponse) {
    ChatModel mockModel =
        new ChatModel() {
          @Override
          public ChatResponse doChat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(cannedResponse)).build();
          }
        };

    ChatModelProvider provider =
        new ChatModelProvider() {
          @Override
          public ModelType type() {
            return ModelType.ANTHROPIC;
          }

          @Override
          public ChatModel get() {
            return mockModel;
          }
        };

    var strategy = new LlmDecompositionStrategy();
    setField(strategy, "chatModelProviders", satisfiedInstance(provider));
    return strategy;
  }

  @SuppressWarnings("unchecked")
  private static Instance<ChatModelProvider> satisfiedInstance(ChatModelProvider provider) {
    return new Instance<>() {
      @Override
      public ChatModelProvider get() {
        return provider;
      }

      @Override
      public boolean isUnsatisfied() {
        return false;
      }

      @Override
      public boolean isResolvable() {
        return true;
      }

      @Override
      public boolean isAmbiguous() {
        return false;
      }

      @Override
      public Instance<ChatModelProvider> select(java.lang.annotation.Annotation... qualifiers) {
        return this;
      }

      @Override
      public <U extends ChatModelProvider> Instance<U> select(
          Class<U> subtype, java.lang.annotation.Annotation... qualifiers) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <U extends ChatModelProvider> Instance<U> select(
          jakarta.enterprise.util.TypeLiteral<U> subtype,
          java.lang.annotation.Annotation... qualifiers) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void destroy(ChatModelProvider instance) {}

      @Override
      public Handle<ChatModelProvider> getHandle() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Iterable<? extends Handle<ChatModelProvider>> handles() {
        throw new UnsupportedOperationException();
      }

      @Override
      public java.util.Iterator<ChatModelProvider> iterator() {
        return List.of(provider).iterator();
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static Instance<ChatModelProvider> unsatisfiedInstance() {
    return new Instance<>() {
      @Override
      public ChatModelProvider get() {
        throw new IllegalStateException("unsatisfied");
      }

      @Override
      public boolean isUnsatisfied() {
        return true;
      }

      @Override
      public boolean isResolvable() {
        return false;
      }

      @Override
      public boolean isAmbiguous() {
        return false;
      }

      @Override
      public Instance<ChatModelProvider> select(java.lang.annotation.Annotation... qualifiers) {
        return this;
      }

      @Override
      public <U extends ChatModelProvider> Instance<U> select(
          Class<U> subtype, java.lang.annotation.Annotation... qualifiers) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <U extends ChatModelProvider> Instance<U> select(
          jakarta.enterprise.util.TypeLiteral<U> subtype,
          java.lang.annotation.Annotation... qualifiers) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void destroy(ChatModelProvider instance) {}

      @Override
      public Handle<ChatModelProvider> getHandle() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Iterable<? extends Handle<ChatModelProvider>> handles() {
        throw new UnsupportedOperationException();
      }

      @Override
      public java.util.Iterator<ChatModelProvider> iterator() {
        return List.<ChatModelProvider>of().iterator();
      }
    };
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      var field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set field " + fieldName, e);
    }
  }
}
