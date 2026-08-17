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

import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.ModelEnricher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

@McpDomain("engine")
@ApplicationScoped
public class EngineModelEnricher implements ModelEnricher {

  private final CaseDefinitionRegistry definitionRegistry;

  @Inject
  public EngineModelEnricher(CaseDefinitionRegistry definitionRegistry) {
    this.definitionRegistry = definitionRegistry;
  }

  @Override
  public String summary() {
    return "Case lifecycle engine — start, suspend, resume, cancel cases. "
        + "Query case instances, definitions, context data, and event logs. "
        + "Subscribe to live lifecycle and context change events.";
  }

  @Override
  public Map<String, Object> state() {
    int count = definitionRegistry.allDefinitions().size();
    return Map.of("registeredDefinitions", count);
  }
}
