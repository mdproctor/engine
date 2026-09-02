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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperHtnTest {

  private CaseDefinition load(String yaml) throws IOException {
    return CaseDefinitionYamlMapper.load(
        new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void fullHtnDefinition_producesDecompositionTree() throws Exception {
    var yaml =
        """
        name: incident-response
        namespace: io.casehub.test
        version: "1.0"
        spec:
          capabilities:
            - name: triage-assessment
            - name: escalation
            - name: auto-resolution
          decomposition:
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
        workers:
          - name: triager
            capabilities: [triage-assessment]
        bindings:
          - name: trigger
            capability: triage-assessment
            on:
              contextChange: {}
        """;
    var def = load(yaml);

    assertThat(def.getDecompositionTree()).isNotNull();
    assertThat(def.getDecompositionTree().name()).isEqualTo("investigate");
    assertThat(def.getDecompositionTree().methods()).hasSize(2);
    assertThat(def.getDecompositionStrategy()).isEqualTo("explicit");
  }

  @Test
  void explicitStrategy_notOverriddenWhenSet() throws Exception {
    var yaml =
        """
        name: test
        namespace: io.casehub.test
        version: "1.0"
        spec:
          capabilities:
            - name: analysis
          decompositionStrategy: goap
          decomposition:
            root:
              name: plan
              methods:
                - tasks:
                    - name: analyze
                      capability: analysis
        workers:
          - name: analyzer
            capabilities: [analysis]
        bindings:
          - name: trigger
            capability: analysis
            on:
              contextChange: {}
        """;
    var def = load(yaml);

    assertThat(def.getDecompositionTree()).isNotNull();
    assertThat(def.getDecompositionStrategy()).isEqualTo("goap");
  }

  @Test
  void noDecomposition_treeIsNull() throws Exception {
    var yaml =
        """
        name: test
        namespace: io.casehub.test
        version: "1.0"
        spec:
          capabilities:
            - name: analysis
        workers:
          - name: analyzer
            capabilities: [analysis]
        bindings:
          - name: trigger
            capability: analysis
            on:
              contextChange: {}
        """;
    var def = load(yaml);

    assertThat(def.getDecompositionTree()).isNull();
  }

  @Test
  void nestedCompound_preservedInTree() throws Exception {
    var yaml =
        """
        name: loan
        namespace: io.casehub.test
        version: "1.0"
        spec:
          capabilities:
            - name: credit-check
            - name: auto-approve
            - name: manual-review
          decomposition:
            root:
              name: loan-application
              methods:
                - tasks:
                    - name: check
                      capability: credit-check
                    - name: approval
                      methods:
                        - guard: ".score > 750"
                          tasks:
                            - name: auto
                              capability: auto-approve
                        - tasks:
                            - name: manual
                              capability: manual-review
        workers:
          - name: checker
            capabilities: [credit-check]
        bindings:
          - name: trigger
            capability: credit-check
            on:
              contextChange: {}
        """;
    var def = load(yaml);

    assertThat(def.getDecompositionTree()).isNotNull();
    var rootMethods = def.getDecompositionTree().methods();
    assertThat(rootMethods).hasSize(1);

    assertThat(rootMethods.get(0).strategy()).isNotNull();
  }
}
