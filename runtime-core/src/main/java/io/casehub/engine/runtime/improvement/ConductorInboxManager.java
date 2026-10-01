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

import io.casehub.api.model.stigmergy.ConductorDecision;
import io.casehub.api.model.stigmergy.ConductorInboxEntry;
import io.casehub.api.model.stigmergy.WatchPattern;
import io.casehub.engine.common.spi.ConductorInboxRepository;
import io.casehub.engine.common.spi.WatchPatternStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ConductorInboxManager {

  private final ConductorInboxRepository inboxRepository;
  private final WatchPatternStore watchPatternStore;

  @Inject
  ConductorInboxManager(
      ConductorInboxRepository inboxRepository, WatchPatternStore watchPatternStore) {
    this.inboxRepository = inboxRepository;
    this.watchPatternStore = watchPatternStore;
  }

  public String enqueue(UUID caseId, ConductorInboxEntry entry, String tenancyId) {
    inboxRepository.save(entry, tenancyId);
    return entry.id();
  }

  public List<ConductorInboxEntry> pending(UUID caseId, String tenancyId) {
    return inboxRepository.findPending(caseId, tenancyId);
  }

  public int pendingCount(UUID caseId, String tenancyId) {
    return inboxRepository.countPending(caseId, tenancyId);
  }

  public List<ConductorInboxEntry> allEntries(UUID caseId, String tenancyId) {
    return inboxRepository.findAll(caseId, tenancyId);
  }

  public void resolve(UUID caseId, String entryId, ConductorDecision decision, String tenancyId) {
    var existing = inboxRepository.findById(caseId, entryId, tenancyId);
    if (existing == null) {
      return;
    }
    var resolved =
        new ConductorInboxEntry(
            existing.caseId(),
            existing.id(),
            existing.stage(),
            decision.outcome(),
            existing.category(),
            existing.areaId(),
            existing.improvementCaseId(),
            existing.summary(),
            existing.escalationTriggers(),
            existing.confidence(),
            existing.queuedAt(),
            Instant.now(),
            existing.timeoutMinutes(),
            decision);
    inboxRepository.save(resolved, tenancyId);
  }

  public List<WatchPattern> activeWatchPatterns(UUID caseId, String tenancyId) {
    return watchPatternStore.findActive(caseId, tenancyId);
  }

  public void addWatchPattern(UUID caseId, WatchPattern pattern, String tenancyId) {
    watchPatternStore.save(caseId, pattern, tenancyId);
  }

  public void removeWatchPattern(UUID caseId, String patternId, String tenancyId) {
    watchPatternStore.remove(caseId, patternId, tenancyId);
  }
}
