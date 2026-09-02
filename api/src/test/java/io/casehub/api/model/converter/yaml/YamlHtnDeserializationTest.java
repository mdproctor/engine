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
package io.casehub.api.model.converter.yaml;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.api.model.converter.CaseDefinitionModule;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import org.junit.jupiter.api.Test;

class YamlHtnDeserializationTest {

  private static final io.casehub.api.engine.ExpressionEngineRegistry JQ_ONLY =
      new io.casehub.api.engine.ExpressionEngineRegistry() {
        @Override
        public ExpressionEvaluator create(String expression, String expressionLang) {
          return new JQExpressionEvaluator(expression);
        }

        @Override
        public void assertLanguageSupported(String expressionLang) {}

        @Override
        public boolean evaluate(
            ExpressionEvaluator evaluator, io.casehub.api.context.CaseContext context) {
          throw new UnsupportedOperationException();
        }

        @Override
        public boolean evaluate(
            ExpressionEvaluator evaluator, com.fasterxml.jackson.databind.JsonNode node) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void validate(ExpressionEvaluator evaluator) {}

        @Override
        public java.util.List<com.fasterxml.jackson.databind.JsonNode> transform(
            ExpressionEvaluator evaluator, com.fasterxml.jackson.databind.JsonNode input) {
          throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<String> extractString(
            ExpressionEvaluator evaluator, io.casehub.api.context.CaseContext context) {
          throw new UnsupportedOperationException();
        }
      };

  private final ObjectMapper mapper =
      new ObjectMapper(new YAMLFactory())
          .registerModule(new CaseDefinitionModule(JQ_ONLY))
          .disable(FAIL_ON_UNKNOWN_PROPERTIES);

  @Test
  void deserializesHtnTree() throws Exception {
    var yaml =
        """
        root:
          name: investigate
          methods:
            - guardLabel: "High severity"
              guard: ".severity == \\"high\\""
              tasks:
                - name: triage
                  capability: triage-assessment
                - name: escalate
                  capability: escalation
            - guardLabel: "Low severity"
              tasks:
                - name: auto-resolve
                  capability: auto-resolution
        """;
    var decomp = mapper.readValue(yaml, YamlDecomposition.class);

    assertThat(decomp.root().name()).isEqualTo("investigate");
    assertThat(decomp.root().methods()).hasSize(2);
    assertThat(decomp.root().methods().get(0).guardLabel()).isEqualTo("High severity");
    assertThat(decomp.root().methods().get(0).guard()).isNotNull();
    assertThat(decomp.root().methods().get(0).tasks()).hasSize(2);
    assertThat(decomp.root().methods().get(0).tasks().get(0).capability())
        .isEqualTo("triage-assessment");
    assertThat(decomp.root().methods().get(0).tasks().get(0).isLeaf()).isTrue();
    assertThat(decomp.root().methods().get(1).guard()).isNull();
  }

  @Test
  void deserializesNestedCompound() throws Exception {
    var yaml =
        """
        root:
          name: loan
          methods:
            - tasks:
                - name: check
                  capability: credit-check
                - name: decision
                  methods:
                    - guard: ".score > 750"
                      tasks:
                        - name: auto
                          capability: auto-approve
                    - tasks:
                        - name: manual
                          capability: manual-review
        """;
    var decomp = mapper.readValue(yaml, YamlDecomposition.class);

    var decision = decomp.root().methods().get(0).tasks().get(1);
    assertThat(decision.isLeaf()).isFalse();
    assertThat(decision.methods()).hasSize(2);
    assertThat(decision.methods().get(0).guard()).isNotNull();
    assertThat(decision.methods().get(1).tasks().get(0).capability()).isEqualTo("manual-review");
  }

  @Test
  void leafNodeWithOptionalFields() throws Exception {
    var yaml =
        """
        root:
          name: task
          methods:
            - tasks:
                - name: analyze
                  capability: analysis
                  description: "Run full analysis"
                  estimatedDuration: PT5M
                  estimatedCost:
                    tokens: 1000
                    apiCalls: 2
        """;
    var decomp = mapper.readValue(yaml, YamlDecomposition.class);
    var leaf = decomp.root().methods().get(0).tasks().get(0);

    assertThat(leaf.description()).isEqualTo("Run full analysis");
    assertThat(leaf.estimatedDuration()).isEqualTo("PT5M");
    assertThat(leaf.estimatedCost()).containsEntry("tokens", 1000);
    assertThat(leaf.estimatedCost()).containsEntry("apiCalls", 2);
  }
}
