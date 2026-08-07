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

    var plan = strategy.decompose(task, context).await().indefinitely();

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

    assertThatThrownBy(() -> strategy.decompose(task, context).await().indefinitely())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void failsOnEmptySteps() {
    var strategy = buildWithMockChatModel("{\"steps\": []}");
    var context = new GoalDecompositionContext(MAPPER.createObjectNode(), 0, List.of());
    var task = new TaskNode.CompoundTask<JsonNode>("goal-1", "goal-1", List.of());

    assertThatThrownBy(() -> strategy.decompose(task, context).await().indefinitely())
        .isInstanceOf(AgentException.class)
        .hasMessageContaining("no steps");
  }

  @Test
  void failsOnMissingStepsField() {
    var strategy = buildWithMockChatModel("{\"result\": \"ok\"}");
    var context = new GoalDecompositionContext(MAPPER.createObjectNode(), 0, List.of());
    var task = new TaskNode.CompoundTask<JsonNode>("goal-1", "goal-1", List.of());

    assertThatThrownBy(() -> strategy.decompose(task, context).await().indefinitely())
        .isInstanceOf(AgentException.class);
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
