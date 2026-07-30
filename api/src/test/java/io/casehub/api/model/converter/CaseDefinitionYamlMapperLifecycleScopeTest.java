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

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ExecutionMode;
import io.casehub.api.model.LifecycleScope;
import io.casehub.api.model.Participation;
import io.casehub.api.model.ScopeActivatedTrigger;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperLifecycleScopeTest {

  private CaseDefinition load(String yaml) throws IOException {
    return CaseDefinitionYamlMapper.load(
        new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void binding_defaults_when_lifecycle_fields_omitted() throws IOException {
    CaseDefinition def =
        load(
            """
            namespace: test
            name: defaults
            version: 1.0.0
            spec:
              capabilities:
                - name: do-work
              workers:
                - name: worker-a
                  capabilities: [do-work]
              bindings:
                - name: b1
                  capability: do-work
                  on:
                    contextChange:
                      filter: ".ready"
            """);

    Binding b = def.getBindings().getFirst();
    assertThat(b.lifecycleScope()).isEqualTo(LifecycleScope.BINDING);
    assertThat(b.participation()).isEqualTo(Participation.PARTICIPANT);
    assertThat(b.executionMode()).isEqualTo(ExecutionMode.TRANSIENT);
  }

  @Test
  void binding_parses_lifecycle_scope_fields() throws IOException {
    CaseDefinition def =
        load(
            """
            namespace: test
            name: scoped
            version: 1.0.0
            spec:
              capabilities:
                - name: monitor
              workers:
                - name: monitor-worker
                  capabilities: [monitor]
              bindings:
                - name: monitoring-binding
                  capability: monitor
                  lifecycleScope: COMPOUND
                  participation: COMPANION
                  executionMode: PERSISTENT
                  on:
                    scopeActivated: {}
            """);

    Binding b = def.getBindings().getFirst();
    assertThat(b.lifecycleScope()).isEqualTo(LifecycleScope.COMPOUND);
    assertThat(b.participation()).isEqualTo(Participation.COMPANION);
    assertThat(b.executionMode()).isEqualTo(ExecutionMode.PERSISTENT);
  }

  @Test
  void binding_parses_scope_activated_trigger() throws IOException {
    CaseDefinition def =
        load(
            """
            namespace: test
            name: scoped
            version: 1.0.0
            spec:
              capabilities:
                - name: observe
              workers:
                - name: observer
                  capabilities: [observe]
              bindings:
                - name: observer-binding
                  capability: observe
                  lifecycleScope: CASE
                  participation: COMPANION
                  executionMode: PERSISTENT
                  on:
                    scopeActivated: {}
            """);

    Binding b = def.getBindings().getFirst();
    assertThat(b.getOn()).isInstanceOf(ScopeActivatedTrigger.class);
  }

  @Test
  void binding_parses_reinvoked_participant() throws IOException {
    CaseDefinition def =
        load(
            """
            namespace: test
            name: reinvoked
            version: 1.0.0
            spec:
              capabilities:
                - name: analyse
              workers:
                - name: analyst
                  capabilities: [analyse]
              bindings:
                - name: analysis-binding
                  capability: analyse
                  lifecycleScope: COMPOUND
                  participation: PARTICIPANT
                  executionMode: REINVOKED
                  on:
                    contextChange:
                      filter: ".request != null"
            """);

    Binding b = def.getBindings().getFirst();
    assertThat(b.lifecycleScope()).isEqualTo(LifecycleScope.COMPOUND);
    assertThat(b.participation()).isEqualTo(Participation.PARTICIPANT);
    assertThat(b.executionMode()).isEqualTo(ExecutionMode.REINVOKED);
  }
}
