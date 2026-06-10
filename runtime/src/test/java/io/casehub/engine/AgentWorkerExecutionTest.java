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
package io.casehub.engine;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.AllOfGoalExpression;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.Worker;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.ai.ModelType;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class AgentWorkerExecutionTest {

  @Inject AgentCaseHub agentCaseHub;

  @Inject CaseInstanceCache caseInstanceCache;

  @Test
  public void agentWorkerExecutesAndCompletesCase() {
    AtomicReference<UUID> caseIdRef = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();

    Map<String, Object> initialContext =
        Map.of(
            "text", "This is a great product!",
            "status", "pending");

    agentCaseHub
        .startCase(initialContext)
        .thenAccept(caseIdRef::set)
        .exceptionally(
            ex -> {
              errorRef.set(ex);
              return null;
            });

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              if (errorRef.get() != null) throw new AssertionError(errorRef.get());
              assertNotNull(caseIdRef.get());
            });

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseIdRef.get());
              assertNotNull(instance);
              assertEquals(CaseStatus.COMPLETED, instance.getState());

              // Verify agent processed the sentiment
              Map<String, Object> context = instance.getCaseContext().getData();
              assertNotNull(context.get("sentiment"));
              assertEquals("POSITIVE", context.get("sentiment"));
            });
  }

  @ApplicationScoped
  public static class AgentCaseHub extends CaseHub {

    private static final CaseDefinition DEFINITION = createDefinition();

    @Override
    public CaseDefinition getDefinition() {
      return DEFINITION;
    }

    private static CaseDefinition createDefinition() {
      // Mock ChatModelProvider that returns fixed sentiment
      ChatModelProvider mockProvider =
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
                  return ChatResponse.builder()
                      .aiMessage(AiMessage.from("{\"sentiment\": \"POSITIVE\"}"))
                      .build();
                }
              };
            }
          };

      // Create AI Agent
      Agent sentimentAgent =
          Agent.builder()
              .systemPrompt(
                  "You are a sentiment analyzer. Analyze the text and return POSITIVE, NEGATIVE, or NEUTRAL.")
              .inputSchema("{ text: .working.text }")
              .outputSchema("{ sentiment: .sentiment }")
              .model(mockProvider)
              .build();

      // Create capability
      Capability sentimentCapability =
          new Capability(
              "analyzeSentiment",
              "{ text: .text }",
              "{ sentiment: .sentiment, status: \"analyzed\" }");

      // Create worker with Agent
      Worker aiWorker =
          Worker.builder()
              .name("sentiment-worker")
              .capabilities(sentimentCapability)
              .function(sentimentAgent)
              .description("AI-powered sentiment analysis worker")
              .build();

      // Create binding to trigger on pending status
      Binding binding =
          Binding.builder()
              .name("trigger-sentiment-analysis")
              .capability(sentimentCapability)
              .on(
                  new ContextChangeTrigger(
                      new JQExpressionEvaluator(".working.status == \"pending\"")))
              .build();

      // Create goal
      Goal analysisComplete =
          new Goal(
              "analysisComplete",
              new JQExpressionEvaluator(".working.status == \"analyzed\""),
              GoalKind.SUCCESS);

      // Create case definition
      CaseDefinition definition = new CaseDefinition("test", "AI Sentiment Analysis", "1.0.0");
      definition.setDsl("0.1");
      definition.setTitle("AI Agent Worker Test Case");

      definition.getCapabilities().add(sentimentCapability);
      definition.getWorkers().add(aiWorker);
      definition.getBindings().add(binding);
      definition.getGoals().add(analysisComplete);

      // Set completion criteria
      definition.setCompletion(
          new GoalBasedCompletion(new AllOfGoalExpression(List.of(analysisComplete)), null));

      return definition;
    }
  }
}
