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

import io.casehub.api.model.AgentTaskTarget;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AgentTaskTargetYamlParserTest {

  @Test
  void parsesAgentTaskBindingTarget() throws Exception {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: agent-task-test
        version: "1.0.0"
        spec:
          capabilities:
            - name: assessRisk
              inputProjection: "."
              outputProjection: "."
          bindings:
            - name: delegate-risk
              agentTask:
                capability: risk-assessment
                domain: financial
                model: claude-sonnet-5-5
                inputMapping: "{ data: .raw }"
                outputMapping: "{ risk: .score }"
              on:
                contextChange:
                  filter: ".needsRisk == true"
        """;

    CaseDefinition def =
        CaseDefinitionYamlMapper.load(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    Binding binding =
        def.getBindings().stream()
            .filter(b -> "delegate-risk".equals(b.getName()))
            .findFirst()
            .orElseThrow();

    assertThat(binding.target()).isInstanceOf(AgentTaskTarget.class);
    AgentTaskTarget target = (AgentTaskTarget) binding.target();
    assertThat(target.capability()).isEqualTo("risk-assessment");
    assertThat(target.domain()).isEqualTo("financial");
    assertThat(target.model()).isEqualTo("claude-sonnet-5-5");
    assertThat(target.inputMapping()).isNotNull();
    assertThat(target.outputMapping()).isNotNull();
  }

  @Test
  void parsesAgentTaskWithCapabilityOnly() throws Exception {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: agent-task-minimal
        version: "1.0.0"
        spec:
          capabilities:
            - name: summarize
              inputProjection: "."
              outputProjection: "."
          bindings:
            - name: delegate-summary
              agentTask:
                capability: summarization
              on:
                contextChange:
                  filter: ".ready == true"
        """;

    CaseDefinition def =
        CaseDefinitionYamlMapper.load(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    Binding binding =
        def.getBindings().stream()
            .filter(b -> "delegate-summary".equals(b.getName()))
            .findFirst()
            .orElseThrow();

    assertThat(binding.target()).isInstanceOf(AgentTaskTarget.class);
    AgentTaskTarget target = (AgentTaskTarget) binding.target();
    assertThat(target.capability()).isEqualTo("summarization");
    assertThat(target.domain()).isNull();
    assertThat(target.model()).isNull();
    assertThat(target.inputMapping()).isNull();
    assertThat(target.outputMapping()).isNull();
  }
}
