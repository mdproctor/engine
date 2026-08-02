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
package io.casehub.engine.internal.engine.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.worker.api.Capability;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContextOutputApplierTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ContextOutputApplier applier;
  private CaseDefinitionRegistry registry;
  private CaseInstance instance;
  private CaseDefinition definition;

  @BeforeEach
  void setUp() {
    Capability cap = Capability.builder().name("cap").inputSchema(".").outputSchema(".").build();
    definition =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .capabilities(cap)
            .build();

    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setNamespace("ns");
    metaModel.setName("test");
    metaModel.setVersion("1.0");

    registry = mock(CaseDefinitionRegistry.class);
    when(registry.getCaseDefinition(any())).thenReturn(definition);

    applier = new ContextOutputApplier();
    applier.caseDefinitionRegistry = registry;
    applier.contextDiffStrategy =
        (before, after) -> {
          ObjectNode diff = MAPPER.createObjectNode();
          after
              .fieldNames()
              .forEachRemaining(
                  key -> {
                    if (!before.has(key) || !before.get(key).equals(after.get(key))) {
                      diff.set(key, after.get(key));
                    }
                  });
          return diff;
        };

    instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setCaseMetaModel(metaModel);
    instance.setCaseContext(new CaseContextImpl());
  }

  @Test
  void nullOutput_returnsNull() {
    assertNull(applier.apply(instance, null, "binding1"));
  }

  @Test
  void emptyOutput_returnsNull() {
    assertNull(applier.apply(instance, Map.of(), "binding1"));
  }

  @Test
  void appliesOutput_defaultStrategy() {
    JsonNode diff = applier.apply(instance, Map.of("key1", "value1"), "binding1");
    assertNotNull(diff);
    assertEquals("value1", instance.getCaseContext().get("key1"));
  }

  @Test
  void appliesOutput_deepMerge() {
    Capability cap = Capability.builder().name("cap").inputSchema(".").outputSchema(".").build();
    Binding binding =
        Binding.builder()
            .name("mergeBinding")
            .capability(cap)
            .on(new ContextChangeTrigger(".x != null"))
            .conflictResolverStrategy("DEEP_MERGE")
            .build();
    definition =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .capabilities(cap)
            .bindings(java.util.List.of(binding))
            .build();
    when(registry.getCaseDefinition(any())).thenReturn(definition);

    instance.getCaseContext().set("key1", Map.of("a", 1));
    JsonNode diff = applier.apply(instance, Map.of("key1", Map.of("b", 2)), "mergeBinding");
    assertNotNull(diff);
    @SuppressWarnings("unchecked")
    Map<String, Object> merged = (Map<String, Object>) instance.getCaseContext().get("key1");
    assertEquals(1, merged.get("a"));
    assertEquals(2, merged.get("b"));
  }

  @Test
  void nullBindingName_defaultsToLastWriterWins() {
    instance.getCaseContext().set("key1", "old");
    JsonNode diff = applier.apply(instance, Map.of("key1", "new"), null);
    assertNotNull(diff);
    assertEquals("new", instance.getCaseContext().get("key1"));
  }

  @Test
  void missingDefinition_defaultsToLastWriterWins() {
    when(registry.getCaseDefinition(any())).thenReturn(null);
    JsonNode diff = applier.apply(instance, Map.of("key1", "value1"), "binding1");
    assertNotNull(diff);
    assertEquals("value1", instance.getCaseContext().get("key1"));
  }

  @Test
  void failStrategy_rejectsOnConflict() {
    Capability cap = Capability.builder().name("cap").inputSchema(".").outputSchema(".").build();
    Binding binding =
        Binding.builder()
            .name("failBinding")
            .capability(cap)
            .on(new ContextChangeTrigger(".x != null"))
            .conflictResolverStrategy("FAIL")
            .build();
    definition =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .capabilities(cap)
            .bindings(java.util.List.of(binding))
            .build();
    when(registry.getCaseDefinition(any())).thenReturn(definition);

    instance.getCaseContext().set("existing", "value");
    assertThrows(
        IllegalStateException.class,
        () -> applier.apply(instance, Map.of("existing", "new"), "failBinding"));
    assertEquals("value", instance.getCaseContext().get("existing"));
  }

  @Test
  void returnsDiff_reflectingChanges() {
    JsonNode diff = applier.apply(instance, Map.of("a", "v1", "b", "v2"), "binding1");
    assertNotNull(diff);
    assertEquals("v1", instance.getCaseContext().get("a"));
    assertEquals("v2", instance.getCaseContext().get("b"));
  }

  @Test
  void evict_removesLockEntry() {
    UUID caseId = instance.getUuid();
    applier.apply(instance, Map.of("key1", "value1"), "binding1");
    applier.evict(caseId);
  }
}
