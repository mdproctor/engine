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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.PredicateBasedCompletion;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperExpressionOverrideTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> SUPPORTED = Set.of("jq", "mvel");

  private final ExpressionEngineRegistry registry =
      new ExpressionEngineRegistry() {
        @Override
        public ExpressionEvaluator create(String expression, String expressionLang) {
          assertLanguageSupported(expressionLang);
          if ("jq".equals(expressionLang)) {
            return new JQExpressionEvaluator(expression);
          }
          return new ExpressionEvaluator() {
            @Override
            public String type() {
              return expressionLang;
            }

            @Override
            public String toString() {
              return expressionLang + ":" + expression;
            }
          };
        }

        @Override
        public void assertLanguageSupported(String expressionLang) {
          if (!SUPPORTED.contains(expressionLang)) {
            throw new IllegalArgumentException("Unsupported language: " + expressionLang);
          }
        }

        @Override
        public boolean evaluate(ExpressionEvaluator evaluator, CaseContext context) {
          throw new UnsupportedOperationException();
        }

        @Override
        public boolean evaluate(ExpressionEvaluator evaluator, JsonNode asNode) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void validate(ExpressionEvaluator evaluator) {}

        @Override
        public List<JsonNode> transform(ExpressionEvaluator evaluator, JsonNode input) {
          throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> extractString(ExpressionEvaluator evaluator, CaseContext context) {
          throw new UnsupportedOperationException();
        }
      };

  // --- resolveExpression unit tests ---

  @Test
  void resolveExpression_plainString_usesDefaultLang() throws Exception {
    JsonNode node = JSON.readTree("\"amount > 1000\"");
    ExpressionEvaluator result = CaseDefinitionYamlMapper.resolveExpression(node, registry, "jq");
    assertThat(result).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(result.type()).isEqualTo("jq");
  }

  @Test
  void resolveExpression_mapOverride_usesMapKey() throws Exception {
    JsonNode node = JSON.readTree("{\"mvel\": \"transaction.amount > 1000\"}");
    ExpressionEvaluator result = CaseDefinitionYamlMapper.resolveExpression(node, registry, "jq");
    assertThat(result.type()).isEqualTo("mvel");
  }

  @Test
  void resolveExpression_mapOverride_jqExplicit() throws Exception {
    JsonNode node = JSON.readTree("{\"jq\": \".amount > 1000\"}");
    ExpressionEvaluator result = CaseDefinitionYamlMapper.resolveExpression(node, registry, "mvel");
    assertThat(result).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(result.type()).isEqualTo("jq");
  }

  @Test
  void resolveExpression_nullNode_returnsNull() {
    ExpressionEvaluator result = CaseDefinitionYamlMapper.resolveExpression(null, registry, "jq");
    assertThat(result).isNull();
  }

  @Test
  void resolveExpression_multipleKeys_throws() throws Exception {
    JsonNode node = JSON.readTree("{\"jq\": \"a\", \"mvel\": \"b\"}");
    assertThatThrownBy(() -> CaseDefinitionYamlMapper.resolveExpression(node, registry, "jq"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("single-key map");
  }

  @Test
  void resolveExpression_unsupportedLanguage_throws() throws Exception {
    JsonNode node = JSON.readTree("{\"drools\": \"rule\"}");
    assertThatThrownBy(() -> CaseDefinitionYamlMapper.resolveExpression(node, registry, "jq"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolveExpression_emptyMap_throws() throws Exception {
    JsonNode node = JSON.readTree("{}");
    assertThatThrownBy(() -> CaseDefinitionYamlMapper.resolveExpression(node, registry, "jq"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolveExpression_numericNode_throws() throws Exception {
    JsonNode node = JSON.readTree("42");
    assertThatThrownBy(() -> CaseDefinitionYamlMapper.resolveExpression(node, registry, "jq"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NUMBER");
  }

  // --- YAML round-trip tests ---

  private CaseDefinition loadYaml(String yaml) throws IOException {
    try (InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
      return CaseDefinitionYamlMapper.load(is, YAML, registry, node -> null);
    }
  }

  @Test
  void load_bindingWhen_mapOverride_producesCorrectEvaluator() throws IOException {
    CaseDefinition def =
        loadYaml(
            """
        name: override-test
        version: "1.0"
        expressionLang: mvel
        spec:
          capabilities:
            - name: cap1
              worker:
                type: test
          bindings:
            - name: mvel-default
              capability: cap1
              on:
                contextChange: {}
              when: "status == 'READY'"
            - name: jq-override
              capability: cap1
              on:
                contextChange: {}
              when: { jq: ".amount > 1000" }
        """);
    Binding mvelBinding =
        def.getBindings().stream()
            .filter(b -> "mvel-default".equals(b.getName()))
            .findFirst()
            .orElseThrow();
    assertThat(mvelBinding.getWhen().type()).isEqualTo("mvel");

    Binding jqBinding =
        def.getBindings().stream()
            .filter(b -> "jq-override".equals(b.getName()))
            .findFirst()
            .orElseThrow();
    assertThat(jqBinding.getWhen().type()).isEqualTo("jq");
    assertThat(jqBinding.getWhen()).isInstanceOf(JQExpressionEvaluator.class);
  }

  @Test
  void load_triggerFilter_mapOverride() throws IOException {
    CaseDefinition def =
        loadYaml(
            """
        name: filter-override-test
        version: "1.0"
        expressionLang: mvel
        spec:
          capabilities:
            - name: cap1
              worker:
                type: test
          bindings:
            - name: jq-filter
              capability: cap1
              on:
                contextChange:
                  filter: { jq: ".status == \\"READY\\"" }
        """);
    Binding binding = def.getBindings().getFirst();
    ContextChangeTrigger trigger = (ContextChangeTrigger) binding.getOn();
    assertThat(trigger.getFilter().type()).isEqualTo("jq");
  }

  @Test
  void load_milestoneCondition_mapOverride() throws IOException {
    CaseDefinition def =
        loadYaml(
            """
        name: milestone-override-test
        version: "1.0"
        expressionLang: mvel
        spec:
          milestones:
            - name: jq-milestone
              condition: { jq: ".progress > 50" }
        """);
    Milestone ms = def.getMilestones().getFirst();
    assertThat(ms.getCompletionCriteria().type()).isEqualTo("jq");
  }

  @Test
  void load_milestoneEntryCriteria_mapOverride() throws IOException {
    CaseDefinition def =
        loadYaml(
            """
                                      name: entry-criteria-override-test
                                      version: "1.0"
                                      expressionLang: mvel
                                      spec:
                                        milestones:
                                          - name: guarded-milestone
                                            condition: "progress > 50"
                                            entryCriteria: { jq: ".ready == true" }
                                      """);
    Milestone ms = def.getMilestones().getFirst();
    assertThat(ms.getCompletionCriteria().type()).isEqualTo("mvel");
    assertThat(ms.getEntryCriteria().type()).isEqualTo("jq");
  }

  @Test
  void load_goalCondition_mapOverride() throws IOException {
    CaseDefinition def =
        loadYaml(
            """
        name: goal-override-test
        version: "1.0"
        expressionLang: mvel
        spec:
          goals:
            - name: jq-goal
              condition: { jq: ".completed == true" }
        """);
    Goal goal = def.getGoals().getFirst();
    assertThat(goal.getCondition().type()).isEqualTo("jq");
  }

  @Test
  void load_doneWhen_mapOverride() throws IOException {
    CaseDefinition def =
        loadYaml(
            """
        name: donewhen-override-test
        version: "1.0"
        expressionLang: mvel
        spec:
          goals:
            - name: done
              condition: "completed == true"
          completion:
            doneWhen: { jq: ".completed == true" }
        """);
    assertThat(def.getCompletion()).isInstanceOf(PredicateBasedCompletion.class);
  }

  @Test
  void load_doneWhen_plainString_defaultsToJq_notDefinitionLang() throws IOException {
    CaseDefinition def =
        loadYaml(
            """
        name: donewhen-default-test
        version: "1.0"
        expressionLang: mvel
        spec:
          goals:
            - name: done
              condition: "completed == true"
          completion:
            doneWhen: ".completed == true"
        """);
    assertThat(def.getCompletion()).isInstanceOf(PredicateBasedCompletion.class);
    PredicateBasedCompletion pbc = (PredicateBasedCompletion) def.getCompletion();
    assertThat(pbc.getDoneWhen().type()).isEqualTo("jq");
  }

  @Test
  void load_plainStringExpressions_unchangedBehavior() throws IOException {
    CaseDefinition def =
        loadYaml(
            """
        name: backward-compat-test
        version: "1.0"
        spec:
          capabilities:
            - name: cap1
              worker:
                type: test
          milestones:
            - name: ms1
              condition: ".progress > 50"
          goals:
            - name: g1
              condition: ".done == true"
          bindings:
            - name: b1
              capability: cap1
              on:
                contextChange:
                  filter: ".x != null"
              when: ".y > 0"
        """);
    assertThat(def.getMilestones().getFirst().getCompletionCriteria().type()).isEqualTo("jq");
    assertThat(def.getGoals().getFirst().getCondition().type()).isEqualTo("jq");
    assertThat(def.getBindings().getFirst().getWhen().type()).isEqualTo("jq");
    ContextChangeTrigger t = (ContextChangeTrigger) def.getBindings().getFirst().getOn();
    assertThat(t.getFilter().type()).isEqualTo("jq");
  }
}
