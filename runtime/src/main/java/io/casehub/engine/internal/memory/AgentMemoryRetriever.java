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
package io.casehub.engine.internal.memory;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.MemoryRetrievalConfig;
import io.casehub.api.model.RetrievedMemory;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryOrder;
import io.casehub.neocortex.memory.MemoryQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AgentMemoryRetriever {

  private static final Logger LOG = Logger.getLogger(AgentMemoryRetriever.class);

  private final Instance<CaseMemoryStore> caseMemoryStore;

  @Inject
  public AgentMemoryRetriever(Instance<CaseMemoryStore> caseMemoryStore) {
    this.caseMemoryStore = caseMemoryStore;
  }

  public List<RetrievedMemory> retrieve(
      String workerName, String tenantId, String capabilityName, CaseDefinition caseDefinition) {
    if (!caseMemoryStore.isResolvable()) return List.of();

    MemoryRetrievalConfig config = caseDefinition.getMemoryRetrieval();
    if (config == null || !config.enabled()) return List.of();

    try {
      Set<String> domains =
          config.domains().isEmpty() ? Set.of("experience", "reflection") : config.domains();
      int perDomainLimit = Math.max(1, config.maxMemories() / domains.size());

      List<List<Memory>> perDomainResults = new ArrayList<>();
      CaseMemoryStore store = caseMemoryStore.get();
      for (String domain : domains) {
        List<Memory> memories =
            store.query(
                MemoryQuery.forEntity(workerName, new MemoryDomain(domain), tenantId)
                    .withQuestion(capabilityName)
                    .withLimit(perDomainLimit)
                    .withOrder(MemoryOrder.SALIENCE));
        perDomainResults.add(memories);
      }

      List<RetrievedMemory> merged = interleaveRoundRobin(perDomainResults, config.maxMemories());
      return List.copyOf(merged);
    } catch (Exception e) {
      LOG.warnf(e, "Failed to retrieve memories for agent %s", workerName);
      return List.of();
    }
  }

  private List<RetrievedMemory> interleaveRoundRobin(
      List<List<Memory>> perDomainResults, int maxMemories) {
    List<RetrievedMemory> result = new ArrayList<>();
    int[] indices = new int[perDomainResults.size()];
    while (result.size() < maxMemories) {
      boolean added = false;
      for (int d = 0; d < perDomainResults.size(); d++) {
        if (result.size() >= maxMemories) break;
        List<Memory> domainList = perDomainResults.get(d);
        if (indices[d] < domainList.size()) {
          result.add(toRetrievedMemory(domainList.get(indices[d])));
          indices[d]++;
          added = true;
        }
      }
      if (!added) break;
    }
    return result;
  }

  private static RetrievedMemory toRetrievedMemory(Memory memory) {
    return new RetrievedMemory(
        memory.memoryId(),
        memory.text(),
        memory.domain().name(),
        memory.createdAt(),
        memory.attributes());
  }
}
