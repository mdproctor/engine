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
package io.casehub.persistence.memory;

import io.casehub.api.model.OnThresholdReached;
import io.casehub.engine.internal.model.SubCaseGroup;
import io.casehub.engine.spi.SubCaseGroupRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link SubCaseGroupRepository} for use in engine unit tests. Activated via {@code
 * quarkus.arc.selected-alternatives} — never active in production.
 */
@Alternative
@ApplicationScoped
public class MemorySubCaseGroupRepository implements SubCaseGroupRepository {

  private final ConcurrentHashMap<String, SubCaseGroup> groups = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, String> childIndex = new ConcurrentHashMap<>();

  private static String key(UUID parentCaseId, String groupId) {
    return parentCaseId + ":" + groupId;
  }

  @Override
  public Uni<SubCaseGroup> getOrCreate(
      UUID parentCaseId,
      String groupId,
      int totalInGroup,
      int requiredCount,
      OnThresholdReached onThresholdReached) {
    String k = key(parentCaseId, groupId);
    SubCaseGroup g =
        groups.computeIfAbsent(
            k,
            __ -> {
              SubCaseGroup ng = new SubCaseGroup();
              ng.setParentCaseId(parentCaseId);
              ng.setGroupId(groupId);
              ng.setInstanceCount(totalInGroup);
              ng.setRequiredCount(requiredCount);
              ng.setOnThresholdReached(
                  onThresholdReached != null ? onThresholdReached : OnThresholdReached.KEEP);
              return ng;
            });
    return Uni.createFrom().item(g);
  }

  @Override
  public Uni<SubCaseGroup> registerChild(UUID parentCaseId, String groupId, UUID childCaseId) {
    String k = key(parentCaseId, groupId);
    SubCaseGroup g = groups.get(k);
    if (g == null) {
      return Uni.createFrom().failure(new IllegalStateException("Group not found: " + k));
    }
    synchronized (g) {
      g.getChildCaseIds().add(childCaseId);
    }
    childIndex.put(childCaseId, k);
    return Uni.createFrom().item(g);
  }

  @Override
  public Uni<SubCaseGroup> incrementCompleted(UUID parentCaseId, String groupId) {
    String k = key(parentCaseId, groupId);
    SubCaseGroup g = groups.get(k);
    if (g == null) {
      return Uni.createFrom().failure(new IllegalStateException("Group not found: " + k));
    }
    synchronized (g) {
      g.setCompletedCount(g.getCompletedCount() + 1);
    }
    return Uni.createFrom().item(g);
  }

  @Override
  public Uni<SubCaseGroup> incrementRejected(UUID parentCaseId, String groupId) {
    String k = key(parentCaseId, groupId);
    SubCaseGroup g = groups.get(k);
    if (g == null) {
      return Uni.createFrom().failure(new IllegalStateException("Group not found: " + k));
    }
    synchronized (g) {
      g.setRejectedCount(g.getRejectedCount() + 1);
    }
    return Uni.createFrom().item(g);
  }

  @Override
  public Uni<Void> markPolicyTriggered(UUID parentCaseId, String groupId) {
    SubCaseGroup g = groups.get(key(parentCaseId, groupId));
    if (g != null) {
      synchronized (g) {
        g.setPolicyTriggered(true);
      }
    }
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<Optional<SubCaseGroup>> findByChildCaseId(UUID childCaseId) {
    String k = childIndex.get(childCaseId);
    if (k == null) {
      return Uni.createFrom().item(Optional.empty());
    }
    return Uni.createFrom().item(Optional.ofNullable(groups.get(k)));
  }
}
