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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.marshaller.YamlMapper;
import io.casehub.api.model.AgentWorkerFunction;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ai.DeferredAgentConfig;
import io.casehub.api.model.converter.CaseDefinitionYamlMapper;
import io.casehub.api.spi.WorkerFunctionProviderRegistry;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.worker.api.Worker;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class SlotAgentWorkerCdiWiringTest {

  @Inject @YamlMapper ObjectMapper yamlMapper;

  @Inject ExpressionEngineRegistry expressionEngineRegistry;

  @Inject WorkerFunctionProviderRegistry workerFunctionProviderRegistry;

  @Inject AgentProvider agentProvider;

  @Inject AgentRegistry agentRegistry;

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;

  @Inject CaseHubRuntime caseHubRuntime;

  @Inject CaseInstanceCache caseInstanceCache;

  private CaseDefinition definition;

  @BeforeEach
  void setUp() throws Exception {
    try (InputStream is =
        getClass().getClassLoader().getResourceAsStream("casehub/agent-slot-test.yaml")) {
      assertThat(is).as("agent-slot-test.yaml must be on the classpath").isNotNull();
      JsonNode yamlNode = yamlMapper.readTree(is);
      definition =
          CaseDefinitionYamlMapper.load(
              yamlNode,
              yamlMapper,
              expressionEngineRegistry,
              workerFunctionProviderRegistry,
              agentProvider);
    }
  }

  @Test
  void yamlParsedWorker_hasDeferredSlotConfig() {
    Worker worker =
        definition.getWorkers().stream()
            .filter(w -> "sentiment-analyzer".equals(w.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Worker 'sentiment-analyzer' not found"));

    assertThat(worker.function()).isInstanceOf(AgentWorkerFunction.class);
    AgentWorkerFunction agentFn = (AgentWorkerFunction) worker.function();
    assertThat(agentFn.isDeferred()).isTrue();
    assertThat(agentFn.deferredConfig()).isInstanceOf(DeferredAgentConfig.SlotConfig.class);

    DeferredAgentConfig.SlotConfig slotConfig =
        (DeferredAgentConfig.SlotConfig) agentFn.deferredConfig();
    assertThat(slotConfig.slot()).isEqualTo("test-sentiment-slot");
    assertThat(slotConfig.inputProjection()).isEqualTo("{ text: .text }");
    assertThat(slotConfig.outputProjection()).isEqualTo("{ sentiment: .sentiment }");
  }

  @Test
  void slotResolution_executesAgentViaRegistry() {
    agentRegistry.register(
        AgentDescriptor.builder()
            .agentId("test-sentiment-agent")
            .name("Sentiment Analyst")
            .slot("test-sentiment-slot")
            .tenancyId("default")
            .briefing("You are a sentiment analyzer. Return JSON with sentiment field.")
            .build());

    caseDefinitionRegistry.registerCaseDefinition(definition);

    Map<String, Object> context = Map.of("text", "Great product!", "status", "pending");
    UUID caseId = caseHubRuntime.startCase(definition, context);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState())
                  .isIn(CaseStatus.COMPLETED, CaseStatus.RUNNING, CaseStatus.FAULTED);
            });
  }
}
