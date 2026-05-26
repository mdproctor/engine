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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.OnThresholdReached;
import io.casehub.engine.common.internal.model.SubCaseGroup;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemorySubCaseGroupRepositoryTest {

  private MemorySubCaseGroupRepository repo;
  private final UUID parentId = UUID.randomUUID();
  private final String groupId = "test-group";

  @BeforeEach
  void setUp() {
    repo = new MemorySubCaseGroupRepository();
  }

  @Test
  void getOrCreate_createsGroup() {
    SubCaseGroup g =
        repo.getOrCreate(parentId, groupId, 3, 2, OnThresholdReached.KEEP).await().indefinitely();
    assertThat(g.getInstanceCount()).isEqualTo(3);
    assertThat(g.getRequiredCount()).isEqualTo(2);
    assertThat(g.getCompletedCount()).isZero();
  }

  @Test
  void getOrCreate_idempotent() {
    repo.getOrCreate(parentId, groupId, 3, 2, OnThresholdReached.KEEP).await().indefinitely();
    SubCaseGroup second =
        repo.getOrCreate(parentId, groupId, 3, 2, OnThresholdReached.KEEP).await().indefinitely();
    assertThat(second.getInstanceCount()).isEqualTo(3);
  }

  @Test
  void registerChild_and_findByChildCaseId() {
    UUID childId = UUID.randomUUID();
    repo.getOrCreate(parentId, groupId, 3, 2, OnThresholdReached.KEEP).await().indefinitely();
    repo.registerChild(parentId, groupId, childId).await().indefinitely();
    Optional<SubCaseGroup> found = repo.findByChildCaseId(childId).await().indefinitely();
    assertThat(found).isPresent();
    assertThat(found.get().getParentCaseId()).isEqualTo(parentId);
  }

  @Test
  void findByChildCaseId_unknownChild_returnsEmpty() {
    Optional<SubCaseGroup> found = repo.findByChildCaseId(UUID.randomUUID()).await().indefinitely();
    assertThat(found).isEmpty();
  }

  @Test
  void incrementCompleted_updatesCount() {
    repo.getOrCreate(parentId, groupId, 3, 2, OnThresholdReached.KEEP).await().indefinitely();
    SubCaseGroup g = repo.incrementCompleted(parentId, groupId).await().indefinitely();
    assertThat(g.getCompletedCount()).isEqualTo(1);
  }

  @Test
  void incrementRejected_updatesCount() {
    repo.getOrCreate(parentId, groupId, 3, 2, OnThresholdReached.KEEP).await().indefinitely();
    SubCaseGroup g = repo.incrementRejected(parentId, groupId).await().indefinitely();
    assertThat(g.getRejectedCount()).isEqualTo(1);
  }

  @Test
  void markPolicyTriggered_setsFlag() {
    repo.getOrCreate(parentId, groupId, 3, 2, OnThresholdReached.KEEP).await().indefinitely();
    repo.markPolicyTriggered(parentId, groupId).await().indefinitely();
    SubCaseGroup g =
        repo.getOrCreate(parentId, groupId, 3, 2, OnThresholdReached.KEEP).await().indefinitely();
    assertThat(g.isPolicyTriggered()).isTrue();
  }
}
