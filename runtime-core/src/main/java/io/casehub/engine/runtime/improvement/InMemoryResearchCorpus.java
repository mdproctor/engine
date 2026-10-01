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
package io.casehub.engine.internal.improvement;

import io.casehub.api.model.stigmergy.HilQueueEntry;
import io.casehub.api.model.stigmergy.ResearchAnalysis;
import io.casehub.api.model.stigmergy.ResearchCandidate;
import io.casehub.api.model.stigmergy.ResearchFinding;
import io.casehub.api.spi.improvement.ResearchCorpus;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InMemoryResearchCorpus implements ResearchCorpus, Resettable {

  private final ConcurrentHashMap<String, ResearchFinding> findings = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, HilQueueEntry> hilQueue = new ConcurrentHashMap<>();

  @Override
  public void store(List<ResearchCandidate> candidates, ResearchAnalysis analysis) {
    for (var finding : analysis.findings()) {
      findings.put(finding.sourceUrl(), finding);
    }
  }

  @Override
  public List<ResearchFinding> search(String query, String capabilityArea, int limit) {
    return findings.values().stream()
        .filter(f -> f.capabilityArea().equals(capabilityArea) || f.technique().contains(query))
        .limit(limit)
        .toList();
  }

  @Override
  public Optional<ResearchFinding> get(String sourceUrl) {
    return Optional.ofNullable(findings.get(sourceUrl));
  }

  @Override
  public List<HilQueueEntry> pendingHilEntries() {
    return List.copyOf(hilQueue.values());
  }

  @Override
  public void addToHilQueue(HilQueueEntry entry) {
    hilQueue.put(entry.sourceUrl(), entry);
  }

  @Override
  public void resolveHilEntry(String sourceUrl, ResearchCandidate retrieved) {
    hilQueue.remove(sourceUrl);
  }

  @Override
  public void reset() {
    findings.clear();
    hilQueue.clear();
  }
}
