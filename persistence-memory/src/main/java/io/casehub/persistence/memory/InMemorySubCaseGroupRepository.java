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
import io.casehub.engine.common.internal.model.SubCaseGroup;
import io.casehub.engine.common.spi.SubCaseGroupRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory blocking {@link SubCaseGroupRepository} for engine unit tests. Canonical implementation
 * — {@link InMemoryReactiveSubCaseGroupRepository} delegates to this.
 */
@Alternative
@ApplicationScoped
public class InMemorySubCaseGroupRepository implements SubCaseGroupRepository {

  private final ConcurrentHashMap<String, SubCaseGroup> groups = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, String> childIndex = new ConcurrentHashMap<>();

  private static String key(UUID parentCaseId, String groupId) {
    return parentCaseId + ":" + groupId;
  }

  @Override
  public SubCaseGroup getOrCreate(
      UUID parentCaseId,
      String groupId,
      int totalInGroup,
      int requiredCount,
      OnThresholdReached onThresholdReached,
      String tenancyId) {
    String k = key(parentCaseId, groupId);
    return groups.computeIfAbsent(
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
  }

  @Override
  public SubCaseGroup registerChild(
      UUID parentCaseId, String groupId, UUID childCaseId, String tenancyId) {
    String k = key(parentCaseId, groupId);
    SubCaseGroup g = groups.get(k);
    if (g == null) {
      throw new IllegalStateException("Group not found: " + k);
    }
    synchronized (g) {
      g.addChildCaseId(childCaseId);
    }
    childIndex.put(childCaseId, k);
    return g;
  }

  @Override
  public SubCaseGroup incrementCompleted(UUID parentCaseId, String groupId, String tenancyId) {
    String k = key(parentCaseId, groupId);
    SubCaseGroup g = groups.get(k);
    if (g == null) {
      throw new IllegalStateException("Group not found: " + k);
    }
    synchronized (g) {
      g.setCompletedCount(g.getCompletedCount() + 1);
    }
    return g;
  }

  @Override
  public SubCaseGroup incrementRejected(UUID parentCaseId, String groupId, String tenancyId) {
    String k = key(parentCaseId, groupId);
    SubCaseGroup g = groups.get(k);
    if (g == null) {
      throw new IllegalStateException("Group not found: " + k);
    }
    synchronized (g) {
      g.setRejectedCount(g.getRejectedCount() + 1);
    }
    return g;
  }

  @Override
  public boolean markPolicyTriggered(UUID parentCaseId, String groupId, String tenancyId) {
    SubCaseGroup g = groups.get(key(parentCaseId, groupId));
    if (g == null) return false;
    synchronized (g) {
      if (g.isPolicyTriggered()) return false;
      g.setPolicyTriggered(true);
      return true;
    }
  }

  @Override
  public Optional<SubCaseGroup> findByChildCaseId(UUID childCaseId, String tenancyId) {
    String k = childIndex.get(childCaseId);
    if (k == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(groups.get(k));
  }
}
