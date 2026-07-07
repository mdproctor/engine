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
package io.casehub.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.converter.CaseDefinitionYamlMapper;
import io.casehub.worker.api.Capability;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests for Binding.producedKeys field — builder, YAML parsing. */
class BindingProducedKeysTest {

  @Test
  void testBuilderWithProducedKeys() {
    Binding binding =
        Binding.builder()
            .name("test-binding")
            .capability(new Capability("test-capability", ".", ".", null))
            .on(new ContextChangeTrigger(".field == true"))
            .producedKeys(Set.of("key1", "key2"))
            .build();

    assertEquals(Set.of("key1", "key2"), binding.getProducedKeys());
  }

  @Test
  void testBuilderWithoutProducedKeys() {
    Binding binding =
        Binding.builder()
            .name("test-binding")
            .capability(new Capability("test-capability", ".", ".", null))
            .on(new ContextChangeTrigger(".field == true"))
            .build();

    assertTrue(binding.getProducedKeys().isEmpty());
  }

  @Test
  void testYamlParsingWithProducedKeys() throws IOException {
    String yaml =
        """
        dsl: 0.1.0
        namespace: test
        name: produced-keys-test
        version: 1.0.0
        spec:
          capabilities:
            - name: test-capability
              inputSchema: .
              outputSchema: .
          workers:
            - name: test-worker
              capabilities:
                - test-capability
          bindings:
            - name: binding-one
              on:
                contextChange:
                  filter: .input != null
              capability: test-capability
              producedKeys:
                - enrichmentScore
                - riskLevel
            - name: binding-two
              on:
                contextChange:
                  filter: .enrichmentScore != null
              capability: test-capability
              producedKeys:
                - finalDecision
        """;

    CaseDefinition definition =
        CaseDefinitionYamlMapper.load(new ByteArrayInputStream(yaml.getBytes()));

    assertEquals(2, definition.getBindings().size());

    Binding bindingOne =
        definition.getBindings().stream()
            .filter(b -> b.getName().equals("binding-one"))
            .findFirst()
            .orElseThrow();

    assertEquals(Set.of("enrichmentScore", "riskLevel"), bindingOne.getProducedKeys());

    Binding bindingTwo =
        definition.getBindings().stream()
            .filter(b -> b.getName().equals("binding-two"))
            .findFirst()
            .orElseThrow();

    assertEquals(Set.of("finalDecision"), bindingTwo.getProducedKeys());
  }

  @Test
  void testYamlParsingWithoutProducedKeys() throws IOException {
    String yaml =
        """
        dsl: 0.1.0
        namespace: test
        name: no-produced-keys-test
        version: 1.0.0
        spec:
          capabilities:
            - name: test-capability
              inputSchema: .
              outputSchema: .
          workers:
            - name: test-worker
              capabilities:
                - test-capability
          bindings:
            - name: binding-one
              on:
                contextChange:
                  filter: .input != null
              capability: test-capability
        """;

    CaseDefinition definition =
        CaseDefinitionYamlMapper.load(new ByteArrayInputStream(yaml.getBytes()));

    assertEquals(1, definition.getBindings().size());

    Binding binding = definition.getBindings().get(0);
    assertTrue(binding.getProducedKeys().isEmpty());
  }
}
