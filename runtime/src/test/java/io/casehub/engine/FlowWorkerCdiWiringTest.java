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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.marshaller.YamlMapper;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.converter.CaseDefinitionYamlMapper;
import io.casehub.api.spi.WorkerFunctionProviderRegistry;
import io.casehub.engine.flow.FlowWorkerFunction;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.worker.api.Worker;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Verifies that YAML case definitions with {@code do:} block workers are properly wired through
 * {@link io.casehub.engine.flow.FlowWorkerFunctionProvider} in the CDI context.
 *
 * <p>Refs casehubio/engine#1336.
 */
@QuarkusTest
public class FlowWorkerCdiWiringTest {

  @Inject @YamlMapper ObjectMapper yamlMapper;

  @Inject ExpressionEngineRegistry expressionEngineRegistry;

  @Inject WorkerFunctionProviderRegistry workerFunctionProviderRegistry;

  @Inject AgentProvider agentProvider;

  @Test
  void yamlDoBlockWorker_producesFlowWorkerFunction() throws Exception {
    try (InputStream is = getClass().getClassLoader().getResourceAsStream("casehub/simple.yaml")) {
      assertThat(is).as("simple.yaml must be on the classpath").isNotNull();
      JsonNode yamlNode = yamlMapper.readTree(is);
      CaseDefinition definition =
          CaseDefinitionYamlMapper.load(
              yamlNode,
              yamlMapper,
              expressionEngineRegistry,
              workerFunctionProviderRegistry,
              agentProvider);

      Worker worker =
          definition.getWorkers().stream()
              .filter(w -> "document-processor".equals(w.name()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("Worker 'document-processor' not found"));

      assertThat(worker.function()).isInstanceOf(FlowWorkerFunction.class);
    }
  }
}
