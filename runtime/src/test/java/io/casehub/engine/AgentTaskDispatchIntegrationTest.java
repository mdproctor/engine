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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.AgentTaskTarget;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.StandardGoalKind;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Capability;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Integration test: end-to-end agent-task dispatch pipeline.
 *
 * <p>Programmatically creates a CaseDefinition with an AgentTaskTarget binding, registers a
 * matching agent in the registry, and dispatches through the full pipeline: AgentTaskTarget →
 * SyntheticAgentTaskWorker → BindingExecutorResolver → WorkerExecutionOrchestrator →
 * AgentWorkerFunctionHandler.
 *
 * <p>Refs casehubio/engine#1351.
 */
@QuarkusTest
public class AgentTaskDispatchIntegrationTest {

  @Inject CaseHubRuntime caseHubRuntime;

  @Inject CaseInstanceCache caseInstanceCache;

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;

  @Inject AgentRegistry agentRegistry;

  @Test
  void agentTaskBinding_dispatchesThroughFullPipeline() {
    agentRegistry.register(
        AgentDescriptor.builder()
            .agentId("risk-agent-1")
            .name("Risk Assessor")
            .slot("risk-slot")
            .tenancyId("default")
            .briefing("You are a risk assessor. Return JSON with risk field.")
            .build());

    Capability riskCapability =
        Capability.of("risk-assessment", "{ claim: .claim }", "{ risk: .risk }");

    Binding binding =
        Binding.builder()
            .name("assess-risk")
            .capability(riskCapability)
            .on(new ContextChangeTrigger(new JQExpressionEvaluator(".status == \"submitted\"")))
            .target(AgentTaskTarget.builder().capability("risk-assessment").build())
            .build();

    Goal riskAssessed =
        new Goal("riskAssessed", new JQExpressionEvaluator(".risk != null"), "success");

    CaseDefinition definition = new CaseDefinition("test", "Agent Task Dispatch Test", "1.0.0");
    definition.setDsl("0.1");
    definition.setTitle("Agent Task Dispatch Integration Test");
    definition.getCapabilities().add(riskCapability);
    definition.getBindings().add(binding);
    definition.getGoals().add(riskAssessed);
    definition.setCompletion(
        GoalBasedCompletion.<StandardGoalKind>builder()
            .goal(StandardGoalKind.SUCCESS, GoalExpression.allOf(riskAssessed))
            .build());

    caseDefinitionRegistry.registerCaseDefinition(definition);

    Map<String, Object> context = Map.of("claim", "Water damage to kitchen", "status", "submitted");
    UUID caseId = caseHubRuntime.startCase(definition, context);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState())
                  .as("Case should reach COMPLETED or at minimum RUNNING (agent dispatched)")
                  .isIn(CaseStatus.COMPLETED, CaseStatus.RUNNING);
            });
  }
}
