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
package io.casehub.engine.internal.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.api.model.ai.AgentException;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.ai.ModelType;
import io.casehub.api.spi.routing.GoalRevisionAction;
import io.casehub.api.spi.routing.GoalRevisionContext;
import io.casehub.api.spi.routing.GoalRevisionProposal;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalOutcomeCounts;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LlmGoalRevisionStrategyTest {

  @Test
  void producesProposalFromLlmResponse() {
    String jsonResponse =
        "{\"revisions\": [{\"goalName\": \"g1\", \"revisedDescription\": \"better desc\", "
            + "\"revisionReason\": \"aligned with outcomes\"}], \"rationale\": \"test\"}";
    LlmGoalRevisionStrategy strategy = strategyWithResponse(jsonResponse);

    GoalRevisionProposal proposal = strategy.revise(buildContext());

    assertThat(proposal.revisions()).hasSize(1);
    assertThat(proposal.revisions().get(0).goalName()).isEqualTo("g1");
    assertThat(proposal.revisions().get(0).action()).isEqualTo(GoalRevisionAction.REVISE);
    assertThat(proposal.revisions().get(0).revisedDescription()).isEqualTo("better desc");
    assertThat(proposal.rationale()).isEqualTo("test");
  }

  @Test
  void emptyRevisionsWhenNoChangesNeeded() {
    String jsonResponse = "{\"revisions\": [], \"rationale\": \"all goals are well-described\"}";
    LlmGoalRevisionStrategy strategy = strategyWithResponse(jsonResponse);

    GoalRevisionProposal proposal = strategy.revise(buildContext());
    assertThat(proposal.revisions()).isEmpty();
  }

  @Test
  void parsesReviseAction() {
    String jsonResponse =
        "{\"revisions\": [{\"goalName\": \"g1\", \"action\": \"REVISE\", "
            + "\"revisedDescription\": \"better desc\", \"revisionReason\": \"aligned\"}], "
            + "\"rationale\": \"test\"}";
    LlmGoalRevisionStrategy strategy = strategyWithResponse(jsonResponse);

    GoalRevisionProposal proposal = strategy.revise(buildContext());
    assertThat(proposal.revisions().get(0).action()).isEqualTo(GoalRevisionAction.REVISE);
    assertThat(proposal.revisions().get(0).revisedDescription()).isEqualTo("better desc");
  }

  @Test
  void parsesAbandonAction() {
    String jsonResponse =
        "{\"revisions\": [{\"goalName\": \"g1\", \"action\": \"ABANDON\", "
            + "\"revisedDescription\": null, \"revisionReason\": \"unachievable\"}], "
            + "\"rationale\": \"test\"}";
    LlmGoalRevisionStrategy strategy = strategyWithResponse(jsonResponse);

    GoalRevisionProposal proposal = strategy.revise(buildContext());
    assertThat(proposal.revisions().get(0).action()).isEqualTo(GoalRevisionAction.ABANDON);
    assertThat(proposal.revisions().get(0).revisedDescription()).isNull();
  }

  @Test
  void parsesCompleteAction() {
    String jsonResponse =
        "{\"revisions\": [{\"goalName\": \"g1\", \"action\": \"COMPLETE\", "
            + "\"revisedDescription\": null, \"revisionReason\": \"achieved\"}], "
            + "\"rationale\": \"test\"}";
    LlmGoalRevisionStrategy strategy = strategyWithResponse(jsonResponse);

    GoalRevisionProposal proposal = strategy.revise(buildContext());
    assertThat(proposal.revisions().get(0).action()).isEqualTo(GoalRevisionAction.COMPLETE);
  }

  @Test
  void missingActionDefaultsToRevise() {
    String jsonResponse =
        "{\"revisions\": [{\"goalName\": \"g1\", \"revisedDescription\": \"new\", "
            + "\"revisionReason\": \"updated\"}], \"rationale\": \"test\"}";
    LlmGoalRevisionStrategy strategy = strategyWithResponse(jsonResponse);

    GoalRevisionProposal proposal = strategy.revise(buildContext());
    assertThat(proposal.revisions().get(0).action()).isEqualTo(GoalRevisionAction.REVISE);
  }

  @Test
  void invalidActionDefaultsToRevise() {
    String jsonResponse =
        "{\"revisions\": [{\"goalName\": \"g1\", \"action\": \"REMOVE\", "
            + "\"revisedDescription\": \"new\", \"revisionReason\": \"updated\"}], "
            + "\"rationale\": \"test\"}";
    LlmGoalRevisionStrategy strategy = strategyWithResponse(jsonResponse);

    GoalRevisionProposal proposal = strategy.revise(buildContext());
    assertThat(proposal.revisions().get(0).action()).isEqualTo(GoalRevisionAction.REVISE);
  }

  @Test
  void invalidActionWithNullDescriptionSkipsEntry() {
    String jsonResponse =
        "{\"revisions\": [{\"goalName\": \"g1\", \"action\": \"DROP\", "
            + "\"revisedDescription\": null, \"revisionReason\": \"not needed\"}], "
            + "\"rationale\": \"test\"}";
    LlmGoalRevisionStrategy strategy = strategyWithResponse(jsonResponse);

    GoalRevisionProposal proposal = strategy.revise(buildContext());
    assertThat(proposal.revisions()).isEmpty();
  }

  @Test
  void malformedRevisionEntrySkippedNotFatal() {
    String jsonResponse =
        "{\"revisions\": ["
            + "{\"goalName\": \"g1\", \"action\": \"ABANDON\", \"revisedDescription\": null, \"revisionReason\": \"drop\"},"
            + "{\"revisionReason\": \"missing goalName\"}"
            + "], \"rationale\": \"test\"}";
    LlmGoalRevisionStrategy strategy = strategyWithResponse(jsonResponse);

    GoalRevisionProposal proposal = strategy.revise(buildContext());
    assertThat(proposal.revisions()).hasSize(1);
    assertThat(proposal.revisions().get(0).goalName()).isEqualTo("g1");
  }

  @Test
  void abandonWithNullDescriptionAllowed() {
    String jsonResponse =
        "{\"revisions\": [{\"goalName\": \"g1\", \"action\": \"ABANDON\", "
            + "\"revisedDescription\": null, \"revisionReason\": \"no longer relevant\"}], "
            + "\"rationale\": \"ok\"}";
    LlmGoalRevisionStrategy strategy = strategyWithResponse(jsonResponse);

    GoalRevisionProposal proposal = strategy.revise(buildContext());
    assertThat(proposal.revisions().get(0).revisedDescription()).isNull();
    assertThat(proposal.revisions().get(0).action()).isEqualTo(GoalRevisionAction.ABANDON);
  }

  @Test
  void systemPromptIncludesActionVocabulary() {
    String jsonResponse = "{\"revisions\": [], \"rationale\": \"ok\"}";
    ChatModel chatModel = mock(ChatModel.class);

    AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
    when(chatModel.chat(any(ChatRequest.class)))
        .thenAnswer(
            inv -> {
              capturedRequest.set(inv.getArgument(0));
              return ChatResponse.builder().aiMessage(AiMessage.from(jsonResponse)).build();
            });

    ChatModelProvider provider =
        new ChatModelProvider() {
          @Override
          public ModelType type() {
            return ModelType.OPENAI;
          }

          @Override
          public ChatModel get() {
            return chatModel;
          }
        };
    @SuppressWarnings("unchecked")
    Instance<ChatModelProvider> instance = mock(Instance.class);
    when(instance.isUnsatisfied()).thenReturn(false);
    when(instance.get()).thenReturn(provider);
    LlmGoalRevisionStrategy strategy = new LlmGoalRevisionStrategy(instance);

    strategy.revise(buildContext());

    String systemPrompt =
        ((dev.langchain4j.data.message.SystemMessage) capturedRequest.get().messages().get(0))
            .text();
    assertThat(systemPrompt).contains("ABANDON");
    assertThat(systemPrompt).contains("COMPLETE");
    assertThat(systemPrompt).contains("REVISE");
  }

  @Test
  @SuppressWarnings("unchecked")
  void failsWhenChatModelProviderAbsent() {
    Instance<ChatModelProvider> absent = mock(Instance.class);
    when(absent.isUnsatisfied()).thenReturn(true);
    LlmGoalRevisionStrategy strategy = new LlmGoalRevisionStrategy(absent);

    try {
      strategy.revise(buildContext());
      fail("Expected failure");
    } catch (Exception e) {
      assertThat(e).isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Test
  void invalidJsonFails() {
    LlmGoalRevisionStrategy strategy = strategyWithResponse("not json");

    try {
      strategy.revise(buildContext());
      fail("Expected failure");
    } catch (Exception e) {
      assertThat(e).isInstanceOf(AgentException.class);
    }
  }

  @SuppressWarnings("unchecked")
  private LlmGoalRevisionStrategy strategyWithResponse(String response) {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(any(ChatRequest.class)))
        .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(response)).build());
    ChatModelProvider provider =
        new ChatModelProvider() {
          @Override
          public ModelType type() {
            return ModelType.OPENAI;
          }

          @Override
          public ChatModel get() {
            return chatModel;
          }
        };
    @SuppressWarnings("unchecked")
    Instance<ChatModelProvider> instance = mock(Instance.class);
    when(instance.isUnsatisfied()).thenReturn(false);
    when(instance.get()).thenReturn(provider);
    return new LlmGoalRevisionStrategy(instance);
  }

  private GoalRevisionContext buildContext() {
    return new GoalRevisionContext(
        "agent-1",
        "tenant-1",
        List.of(
            new AgentGoal(
                "g1", "original desc", GoalPriority.PRIMARY, Visibility.PUBLIC, List.of())),
        Map.of("g1", new GoalOutcomeCounts(8, 2)));
  }
}
