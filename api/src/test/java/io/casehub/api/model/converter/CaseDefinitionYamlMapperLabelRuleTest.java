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

import io.casehub.api.model.CaseDefinition;
import io.casehub.platform.api.label.LabelAction;
import io.casehub.platform.api.label.LabelRule;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperLabelRuleTest {

  @Test
  void parsesLabelRulesFromYaml() throws Exception {
    String yaml =
        """
        namespace: test
        name: test-case
        version: "1.0"
        labelRules:
          - name: high-priority
            when: '.severity == "HIGH"'
            actions:
              - add: "priority/high"
          - name: resolved
            when: '.status == "resolved"'
            actions:
              - remove: "triage/pending"
              - add: "resolved/done"
        """;
    CaseDefinition def = load(yaml);
    assertThat(def.getLabelRules()).hasSize(2);

    LabelRule rule0 = def.getLabelRules().get(0);
    assertThat(rule0.name()).isEqualTo("high-priority");
    assertThat(rule0.actions()).hasSize(1);
    assertThat(rule0.actions().get(0)).isInstanceOf(LabelAction.Add.class);
    assertThat(((LabelAction.Add) rule0.actions().get(0)).label()).isEqualTo("priority/high");

    LabelRule rule1 = def.getLabelRules().get(1);
    assertThat(rule1.name()).isEqualTo("resolved");
    assertThat(rule1.actions()).hasSize(2);
    assertThat(rule1.actions().get(0)).isInstanceOf(LabelAction.Remove.class);
    assertThat(((LabelAction.Remove) rule1.actions().get(0)).label()).isEqualTo("triage/pending");
    assertThat(rule1.actions().get(1)).isInstanceOf(LabelAction.Add.class);
  }

  @Test
  void noLabelRules_returnsEmptyList() throws Exception {
    String yaml =
        """
        namespace: test
        name: test-case
        version: "1.0"
        """;
    CaseDefinition def = load(yaml);
    assertThat(def.getLabelRules()).isEmpty();
  }

  @Test
  void labelRuleCondition_evaluates() throws Exception {
    String yaml =
        """
        namespace: test
        name: test-case
        version: "1.0"
        labelRules:
          - name: high-priority
            when: '.severity == "HIGH"'
            actions:
              - add: "priority/high"
        """;
    CaseDefinition def = load(yaml);
    LabelRule rule = def.getLabelRules().get(0);
    List<LabelAction> actions = LabelRule.evaluate(List.of(rule), Map.of("severity", "HIGH"));
    assertThat(actions).hasSize(1);
    assertThat(((LabelAction.Add) actions.get(0)).label()).isEqualTo("priority/high");
  }

  @Test
  void labelRuleCondition_nonMatching_returnsNoActions() throws Exception {
    String yaml =
        """
        namespace: test
        name: test-case
        version: "1.0"
        labelRules:
          - name: high-priority
            when: '.severity == "HIGH"'
            actions:
              - add: "priority/high"
        """;
    CaseDefinition def = load(yaml);
    LabelRule rule = def.getLabelRules().get(0);
    List<LabelAction> actions = LabelRule.evaluate(List.of(rule), Map.of("severity", "LOW"));
    assertThat(actions).isEmpty();
  }

  private CaseDefinition load(String yaml) throws java.io.IOException {
    return CaseDefinitionYamlMapper.load(
        new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }
}
