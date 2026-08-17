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
package io.casehub.engine.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EngineModelEnricherTest {

  @Test
  void summary_describes_engine_domain() {
    CaseDefinitionRegistry registry = mock(CaseDefinitionRegistry.class);
    EngineModelEnricher enricher = new EngineModelEnricher(registry);

    String summary = enricher.summary();

    assertThat(summary).isNotBlank();
    assertThat(summary.toLowerCase()).containsAnyOf("case", "engine");
  }

  @Test
  void state_includes_definition_count() {
    CaseDefinitionRegistry registry = mock(CaseDefinitionRegistry.class);
    @SuppressWarnings("unchecked")
    java.util.Collection<CaseDefinition> defs =
        (java.util.Collection<CaseDefinition>) (java.util.Collection<?>) List.of("a", "b");
    when(registry.allDefinitions()).thenReturn(defs);

    EngineModelEnricher enricher = new EngineModelEnricher(registry);
    Map<String, Object> state = enricher.state();

    assertThat(state).containsEntry("registeredDefinitions", 2);
  }

  @Test
  void state_handles_empty_registry() {
    CaseDefinitionRegistry registry = mock(CaseDefinitionRegistry.class);
    when(registry.allDefinitions()).thenReturn(List.of());

    EngineModelEnricher enricher = new EngineModelEnricher(registry);
    Map<String, Object> state = enricher.state();

    assertThat(state).containsEntry("registeredDefinitions", 0);
  }
}
