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
package io.casehub.engine.runtime.worker;

import io.casehub.eidos.api.AgentMatch;
import io.casehub.eidos.api.AgentSelection;
import io.casehub.eidos.api.AgentSelector;
import io.casehub.eidos.api.SelectionContext;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@DefaultBean
@ApplicationScoped
public class NoOpAgentSelector implements AgentSelector {

  @Override
  public AgentSelection select(List<AgentMatch> candidates, SelectionContext context) {
    if (candidates.isEmpty()) {
      return new AgentSelection.NoneQualified("No candidates available");
    }
    AgentMatch best = candidates.get(0);
    return new AgentSelection.Selected(
        best.descriptor(), best.resolvedCapability(), 1.0, "first-match (no-op)");
  }
}
