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

import io.casehub.engine.common.spi.ImprovementBlockStore;
import io.casehub.engine.common.spi.Resettable;
import io.quarkus.arc.DefaultBean;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@DefaultBean
@ApplicationScoped
public class InMemoryImprovementBlockStore implements ImprovementBlockStore, Resettable {

  private final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, UUID>> blocks =
      new ConcurrentHashMap<>();

  @Override
  public void save(
      UUID caseId, UUID improvementCaseId, UUID blockerImprovementId, String tenancyId) {
    blocks
        .computeIfAbsent(caseId, k -> new ConcurrentHashMap<>())
        .put(improvementCaseId, blockerImprovementId);
  }

  @Override
  public void remove(UUID caseId, UUID improvementCaseId, String tenancyId) {
    var caseBlocks = blocks.get(caseId);
    if (caseBlocks != null) {
      caseBlocks.remove(improvementCaseId);
    }
  }

  @Override
  public boolean isBlocked(UUID caseId, UUID improvementCaseId, String tenancyId) {
    var caseBlocks = blocks.get(caseId);
    return caseBlocks != null && caseBlocks.containsKey(improvementCaseId);
  }

  @Nullable
  @Override
  public UUID blockedBy(UUID caseId, UUID improvementCaseId, String tenancyId) {
    var caseBlocks = blocks.get(caseId);
    return caseBlocks != null ? caseBlocks.get(improvementCaseId) : null;
  }

  @Override
  public void reset() {
    blocks.clear();
  }
}
