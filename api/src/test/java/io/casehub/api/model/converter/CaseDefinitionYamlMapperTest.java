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
package io.casehub.api.model.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.Worker;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperTest {

  @Test
  void load_minimalYaml_loadsSuccessfully() throws IOException {
    String yaml =
        """
        namespace: test
        name: Minimal Case
        version: 1.0.0
        spec:
          capabilities: []
          workers: []
          bindings: []
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getNamespace()).isEqualTo("test");
    assertThat(def.getName()).isEqualTo("Minimal Case");
    assertThat(def.getVersion()).isEqualTo("1.0.0");
    assertThat(def.getCapabilities()).isEmpty();
    assertThat(def.getWorkers()).isEmpty();
    assertThat(def.getBindings()).isEmpty();
  }

  @Test
  void load_fullYaml_convertsAllElements() throws IOException {
    String yaml =
        """
        namespace: test
        name: Full Case
        version: 1.0.0
        title: Complete Test Case
        dsl: 1.0.0
        spec:
          capabilities:
            - name: validate
              inputSchema: ".request"
              outputSchema: ".valid"
              description: Validates input
          workers:
            - name: validator-worker
              capabilities:
                - validate
              description: Worker that validates
          bindings:
            - name: validation-binding
              capability: validate
              on:
                contextChange:
                  filter: ".status == \\"pending\\""
          milestones:
            - name: validation-complete
              condition: ".valid == true"
              description: Validation milestone
          goals:
            - name: process-complete
              condition: ".processed == true"
              description: Processing goal
          completion:
            success:
              allOf:
                - process-complete
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getNamespace()).isEqualTo("test");
    assertThat(def.getName()).isEqualTo("Full Case");
    assertThat(def.getVersion()).isEqualTo("1.0.0");
    assertThat(def.getTitle()).isEqualTo("Complete Test Case");
    assertThat(def.getDsl()).isEqualTo("1.0.0");

    // Capabilities
    assertThat(def.getCapabilities()).hasSize(1);
    Capability cap = def.getCapabilities().get(0);
    assertThat(cap.getName()).isEqualTo("validate");
    assertThat(cap.getInputSchema()).isEqualTo(".request");
    assertThat(cap.getOutputSchema()).isEqualTo(".valid");
    assertThat(cap.getDescription()).isEqualTo("Validates input");

    // Workers
    assertThat(def.getWorkers()).hasSize(1);
    Worker worker = def.getWorkers().get(0);
    assertThat(worker.getName()).isEqualTo("validator-worker");
    assertThat(worker.getCapabilities()).containsExactly(cap);
    assertThat(worker.getDescription()).isEqualTo("Worker that validates");

    // Bindings
    assertThat(def.getBindings()).hasSize(1);
    Binding binding = def.getBindings().get(0);
    assertThat(binding.getName()).isEqualTo("validation-binding");
    assertThat(binding.target()).isInstanceOf(io.casehub.api.model.CapabilityTarget.class);
    assertThat(((io.casehub.api.model.CapabilityTarget) binding.target()).capability())
        .isEqualTo(cap);
    assertThat(binding.getOn()).isInstanceOf(ContextChangeTrigger.class);
    ContextChangeTrigger trigger = (ContextChangeTrigger) binding.getOn();
    assertThat(trigger.getFilter()).isInstanceOf(JQExpressionEvaluator.class);
    JQExpressionEvaluator filter = (JQExpressionEvaluator) trigger.getFilter();
    assertThat(filter.expression()).isEqualTo(".status == \"pending\"");

    // Milestones
    assertThat(def.getMilestones()).hasSize(1);
    Milestone milestone = def.getMilestones().get(0);
    assertThat(milestone.getName()).isEqualTo("validation-complete");
    assertThat(milestone.getCompletionCriteria()).isInstanceOf(JQExpressionEvaluator.class);
    JQExpressionEvaluator milestoneCondition =
        (JQExpressionEvaluator) milestone.getCompletionCriteria();
    assertThat(milestoneCondition.expression()).isEqualTo(".valid == true");
    assertThat(milestone.getDescription()).isEqualTo("Validation milestone");

    // Goals
    assertThat(def.getGoals()).hasSize(1);
    Goal goal = def.getGoals().get(0);
    assertThat(goal.getName()).isEqualTo("process-complete");
    assertThat(goal.getCondition()).isInstanceOf(JQExpressionEvaluator.class);
    JQExpressionEvaluator goalCondition = (JQExpressionEvaluator) goal.getCondition();
    assertThat(goalCondition.expression()).isEqualTo(".processed == true");
    assertThat(goal.getDescription()).isEqualTo("Processing goal");

    // Completion
    assertThat(def.getCompletion()).isNotNull();
  }

  @Test
  void load_subCaseBinding_convertsSuccessfully() throws IOException {
    String yaml =
        """
        namespace: test
        name: SubCase Test
        version: 1.0.0
        spec:
          bindings:
            - name: approval-binding
              subCase:
                namespace: approvals
                name: manager-approval
                version: 1.0.0
                waitForCompletion: true
              on:
                contextChange:
                  filter: ".amount > 1000"
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getBindings()).hasSize(1);
    Binding binding = def.getBindings().get(0);
    assertThat(binding.getName()).isEqualTo("approval-binding");
    assertThat(binding.target()).isInstanceOf(io.casehub.api.model.SubCaseTarget.class);
    io.casehub.api.model.SubCase subCase =
        ((io.casehub.api.model.SubCaseTarget) binding.target()).subCase();
    assertThat(subCase.namespace()).isEqualTo("approvals");
    assertThat(subCase.name()).isEqualTo("manager-approval");
    assertThat(subCase.version()).isEqualTo("1.0.0");
    assertThat(subCase.waitForCompletion()).isTrue();
  }

  @Test
  void load_nullInputStream_throwsException() {
    assertThatThrownBy(() -> CaseDefinitionYamlMapper.load(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("InputStream cannot be null");
  }

  @Test
  void load_invalidYaml_throwsIOException() {
    String invalidYaml = "{ this is not valid yaml: [}";
    InputStream is = new ByteArrayInputStream(invalidYaml.getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> CaseDefinitionYamlMapper.load(is)).isInstanceOf(IOException.class);
  }

  @Test
  void load_workerWithAgent_convertsAgentToApiModel() throws IOException {
    String yaml =
        """
        namespace: test
        name: Agent Test
        version: 1.0.0
        spec:
          capabilities:
            - name: analyze
              inputSchema: "{ text: .text }"
              outputSchema: "{ result: .result }"
          workers:
            - name: analyzer-worker
              capabilities:
                - analyze
              agent:
                systemPrompt: "You are an analyzer"
                inputSchema: "{ text: .text }"
                outputSchema: "{ result: .result }"
                model:
                  openai:
                    apiKey: "test-key"
                    modelName: "gpt-4"
                    temperature: 0.7
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getWorkers()).hasSize(1);
    Worker worker = def.getWorkers().get(0);
    assertThat(worker.getName()).isEqualTo("analyzer-worker");
    assertThat(worker.getFunction()).isNotNull();

    // Verify that the agent was converted to API model (Agent is the value in the function holder)
    Object value = worker.getFunction().getValue();
    assertThat(value).isInstanceOf(Agent.class);
  }
}
