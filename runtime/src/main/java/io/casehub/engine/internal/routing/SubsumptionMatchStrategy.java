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
package io.casehub.engine.internal.routing;

import io.casehub.api.spi.routing.CandidateMatchingContext;
import io.casehub.api.spi.routing.CandidateMatchingStrategy;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.CapabilityResolver;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.worker.api.Worker;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.Unremovable;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

@DefaultBean
@ApplicationScoped
@Unremovable
public class SubsumptionMatchStrategy implements CandidateMatchingStrategy {

  private final VocabularyRegistry vocabularyRegistry;

  @Inject
  public SubsumptionMatchStrategy(VocabularyRegistry vocabularyRegistry) {
    this.vocabularyRegistry = vocabularyRegistry;
  }

  @Override
  public String id() {
    return "subsumption";
  }

  @Override
  public Uni<List<Worker>> match(CandidateMatchingContext context) {
    return Uni.createFrom()
        .item(
            () -> {
              List<Worker> matched = new ArrayList<>();
              for (Worker worker : context.workers()) {
                if (worker.capabilityNames().contains(context.capabilityName())) {
                  matched.add(worker);
                  continue;
                }
                AgentDescriptor descriptor =
                    context.caseDefinition() != null
                        ? context.caseDefinition().agentDescriptorFor(worker.name()).orElse(null)
                        : null;
                if (descriptor != null && !descriptor.capabilities().isEmpty()) {
                  var resolved =
                      CapabilityResolver.resolve(
                          descriptor.capabilities(), context.capabilityName(), vocabularyRegistry);
                  if (resolved != null) {
                    matched.add(worker);
                  }
                }
              }
              return List.copyOf(matched);
            });
  }
}
