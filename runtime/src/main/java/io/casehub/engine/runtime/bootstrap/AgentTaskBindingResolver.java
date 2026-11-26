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
package io.casehub.engine.runtime.bootstrap;

import io.casehub.api.model.AgentTaskTarget;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AgentTaskBindingResolver {

  private static final Logger LOG = Logger.getLogger(AgentTaskBindingResolver.class);

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject AgentRegistry agentRegistry;
  @Inject CurrentPrincipal currentPrincipal;

  void onStart(@Observes @Priority(2700) StartupEvent event) {
    validate();
  }

  ValidationResult validate() {
    String tenancyId = currentPrincipal.tenancyId();
    int resolved = 0;
    int unresolved = 0;

    for (CaseDefinition def : caseDefinitionRegistry.allDefinitions()) {
      if (def.getBindings() == null) {
        continue;
      }
      for (Binding binding : def.getBindings()) {
        if (!(binding.target() instanceof AgentTaskTarget at)) {
          continue;
        }
        var matches =
            agentRegistry.find(
                AgentQuery.byCapabilityAndDomain(at.capability(), at.domain(), tenancyId));
        if (matches.isEmpty()) {
          LOG.warnf(
              "No agent found for agent-task binding '%s' in case '%s'"
                  + " (capability=%s, domain=%s)",
              binding.getName(), def.getName(), at.capability(), at.domain());
          unresolved++;
        } else {
          resolved++;
        }
      }
    }

    if (resolved + unresolved > 0) {
      LOG.infof(
          "AgentTaskBindingResolver: validated %d agent-task binding(s)"
              + " across case definitions — %d resolved, %d unresolved",
          resolved + unresolved, resolved, unresolved);
    }

    return new ValidationResult(resolved, unresolved);
  }

  record ValidationResult(int resolved, int unresolved) {}
}
