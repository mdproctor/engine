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
package io.casehub.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.OnThresholdReached;
import io.casehub.engine.internal.model.SubCaseGroup;
import io.casehub.engine.spi.SubCaseGroupRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JpaSubCaseGroupRepositoryTest {

  @Inject SubCaseGroupRepository repository;

  @Test
  void getOrCreate_createsNewGroup() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-create-" + UUID.randomUUID().toString().substring(0, 8);

    SubCaseGroup result =
        run(() -> repository.getOrCreate(parentCaseId, groupId, 3, 2, OnThresholdReached.CANCEL));

    assertThat(result).isNotNull();
    assertThat(result.getParentCaseId()).isEqualTo(parentCaseId);
    assertThat(result.getGroupId()).isEqualTo(groupId);
    assertThat(result.getInstanceCount()).isEqualTo(3);
    assertThat(result.getRequiredCount()).isEqualTo(2);
    assertThat(result.getCompletedCount()).isZero();
    assertThat(result.getRejectedCount()).isZero();
    assertThat(result.isPolicyTriggered()).isFalse();
    assertThat(result.getOnThresholdReached()).isEqualTo(OnThresholdReached.CANCEL);
    assertThat(result.getChildCaseIds()).isEmpty();
  }

  @Test
  void getOrCreate_returnsExistingGroup() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-existing-" + UUID.randomUUID().toString().substring(0, 8);

    SubCaseGroup first =
        run(() -> repository.getOrCreate(parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));
    run(() -> repository.incrementCompleted(parentCaseId, groupId));

    SubCaseGroup second =
        run(() -> repository.getOrCreate(parentCaseId, groupId, 5, 4, OnThresholdReached.CANCEL));

    // Should return existing group, ignoring new parameters
    assertThat(second.getParentCaseId()).isEqualTo(parentCaseId);
    assertThat(second.getGroupId()).isEqualTo(groupId);
    assertThat(second.getInstanceCount()).isEqualTo(3); // original value, not 5
    assertThat(second.getRequiredCount()).isEqualTo(2); // original value, not 4
    assertThat(second.getOnThresholdReached()).isEqualTo(OnThresholdReached.KEEP); // original
    assertThat(second.getCompletedCount()).isEqualTo(1); // incremented earlier
  }

  @Test
  void getOrCreate_setsDefaultOnThresholdReached() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-default-" + UUID.randomUUID().toString().substring(0, 8);

    SubCaseGroup result = run(() -> repository.getOrCreate(parentCaseId, groupId, 3, 2, null));

    assertThat(result.getOnThresholdReached()).isEqualTo(OnThresholdReached.KEEP);
  }

  @Test
  void getOrCreate_allowsDifferentGroupsForSameParent() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId1 = "group-1-" + UUID.randomUUID().toString().substring(0, 8);
    String groupId2 = "group-2-" + UUID.randomUUID().toString().substring(0, 8);

    SubCaseGroup g1 =
        run(() -> repository.getOrCreate(parentCaseId, groupId1, 2, 1, OnThresholdReached.KEEP));
    SubCaseGroup g2 =
        run(() -> repository.getOrCreate(parentCaseId, groupId2, 3, 2, OnThresholdReached.CANCEL));

    assertThat(g1.getGroupId()).isEqualTo(groupId1);
    assertThat(g2.getGroupId()).isEqualTo(groupId2);
    assertThat(g1.getInstanceCount()).isEqualTo(2);
    assertThat(g2.getInstanceCount()).isEqualTo(3);
  }

  @Test
  void getOrCreate_allowsSameGroupIdForDifferentParents() {
    UUID parentCaseId1 = UUID.randomUUID();
    UUID parentCaseId2 = UUID.randomUUID();
    String groupId = "shared-group-" + UUID.randomUUID().toString().substring(0, 8);

    SubCaseGroup g1 =
        run(() -> repository.getOrCreate(parentCaseId1, groupId, 2, 1, OnThresholdReached.KEEP));
    SubCaseGroup g2 =
        run(() -> repository.getOrCreate(parentCaseId2, groupId, 3, 2, OnThresholdReached.CANCEL));

    assertThat(g1.getParentCaseId()).isEqualTo(parentCaseId1);
    assertThat(g2.getParentCaseId()).isEqualTo(parentCaseId2);
    assertThat(g1.getInstanceCount()).isEqualTo(2);
    assertThat(g2.getInstanceCount()).isEqualTo(3);
  }

  @Test
  void registerChild_addsChildToGroup() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-child-" + UUID.randomUUID().toString().substring(0, 8);
    UUID childCaseId = UUID.randomUUID();

    run(() -> repository.getOrCreate(parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));
    SubCaseGroup result = run(() -> repository.registerChild(parentCaseId, groupId, childCaseId));

    assertThat(result.getChildCaseIds()).containsExactly(childCaseId);
  }

  @Test
  void registerChild_allowsMultipleChildren() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-multi-" + UUID.randomUUID().toString().substring(0, 8);
    UUID child1 = UUID.randomUUID();
    UUID child2 = UUID.randomUUID();
    UUID child3 = UUID.randomUUID();

    run(() -> repository.getOrCreate(parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));
    run(() -> repository.registerChild(parentCaseId, groupId, child1));
    run(() -> repository.registerChild(parentCaseId, groupId, child2));
    SubCaseGroup result = run(() -> repository.registerChild(parentCaseId, groupId, child3));

    assertThat(result.getChildCaseIds()).containsExactlyInAnyOrder(child1, child2, child3);
  }

  @Test
  void registerChild_failsForNonExistentGroup() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "non-existent-" + UUID.randomUUID().toString().substring(0, 8);
    UUID childCaseId = UUID.randomUUID();

    assertThatThrownBy(
            () -> run(() -> repository.registerChild(parentCaseId, groupId, childCaseId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Group not found");
  }

  @Test
  void registerChild_idempotent() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-idempotent-" + UUID.randomUUID().toString().substring(0, 8);
    UUID childCaseId = UUID.randomUUID();

    run(() -> repository.getOrCreate(parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));
    run(() -> repository.registerChild(parentCaseId, groupId, childCaseId));
    SubCaseGroup result = run(() -> repository.registerChild(parentCaseId, groupId, childCaseId));

    assertThat(result.getChildCaseIds()).containsExactly(childCaseId);
  }

  @Test
  void incrementCompleted_incrementsCounter() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-increment-" + UUID.randomUUID().toString().substring(0, 8);

    run(() -> repository.getOrCreate(parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));
    SubCaseGroup result = run(() -> repository.incrementCompleted(parentCaseId, groupId));

    assertThat(result.getCompletedCount()).isEqualTo(1);
  }

  @Test
  void incrementCompleted_allowsMultipleIncrements() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-multi-inc-" + UUID.randomUUID().toString().substring(0, 8);

    run(() -> repository.getOrCreate(parentCaseId, groupId, 5, 3, OnThresholdReached.KEEP));
    run(() -> repository.incrementCompleted(parentCaseId, groupId));
    run(() -> repository.incrementCompleted(parentCaseId, groupId));
    SubCaseGroup result = run(() -> repository.incrementCompleted(parentCaseId, groupId));

    assertThat(result.getCompletedCount()).isEqualTo(3);
  }

  @Test
  void incrementCompleted_failsForNonExistentGroup() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "non-existent-" + UUID.randomUUID().toString().substring(0, 8);

    assertThatThrownBy(() -> run(() -> repository.incrementCompleted(parentCaseId, groupId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Group not found");
  }

  @Test
  void incrementRejected_incrementsCounter() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-reject-" + UUID.randomUUID().toString().substring(0, 8);

    run(() -> repository.getOrCreate(parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));
    SubCaseGroup result = run(() -> repository.incrementRejected(parentCaseId, groupId));

    assertThat(result.getRejectedCount()).isEqualTo(1);
  }

  @Test
  void incrementRejected_allowsMultipleIncrements() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-multi-reject-" + UUID.randomUUID().toString().substring(0, 8);

    run(() -> repository.getOrCreate(parentCaseId, groupId, 5, 3, OnThresholdReached.KEEP));
    run(() -> repository.incrementRejected(parentCaseId, groupId));
    SubCaseGroup result = run(() -> repository.incrementRejected(parentCaseId, groupId));

    assertThat(result.getRejectedCount()).isEqualTo(2);
  }

  @Test
  void incrementRejected_failsForNonExistentGroup() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "non-existent-" + UUID.randomUUID().toString().substring(0, 8);

    assertThatThrownBy(() -> run(() -> repository.incrementRejected(parentCaseId, groupId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Group not found");
  }

  @Test
  void incrementCompleted_and_incrementRejected_independent() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-independent-" + UUID.randomUUID().toString().substring(0, 8);

    run(() -> repository.getOrCreate(parentCaseId, groupId, 5, 3, OnThresholdReached.KEEP));
    run(() -> repository.incrementCompleted(parentCaseId, groupId));
    run(() -> repository.incrementRejected(parentCaseId, groupId));
    run(() -> repository.incrementCompleted(parentCaseId, groupId));
    SubCaseGroup result = run(() -> repository.incrementRejected(parentCaseId, groupId));

    assertThat(result.getCompletedCount()).isEqualTo(2);
    assertThat(result.getRejectedCount()).isEqualTo(2);
  }

  @Test
  void markPolicyTriggered_returnsTrueFirstTime() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-policy-" + UUID.randomUUID().toString().substring(0, 8);

    run(() -> repository.getOrCreate(parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));
    Boolean result = run(() -> repository.markPolicyTriggered(parentCaseId, groupId));

    assertThat(result).isTrue();
  }

  @Test
  void markPolicyTriggered_returnsFalseSecondTime() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-policy-idempotent-" + UUID.randomUUID().toString().substring(0, 8);

    run(() -> repository.getOrCreate(parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));
    run(() -> repository.markPolicyTriggered(parentCaseId, groupId));
    Boolean secondCall = run(() -> repository.markPolicyTriggered(parentCaseId, groupId));

    assertThat(secondCall).isFalse();
  }

  @Test
  void markPolicyTriggered_returnsFalseForNonExistentGroup() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "non-existent-" + UUID.randomUUID().toString().substring(0, 8);

    Boolean result = run(() -> repository.markPolicyTriggered(parentCaseId, groupId));

    assertThat(result).isFalse();
  }

  @Test
  void markPolicyTriggered_persistsFlag() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-policy-persist-" + UUID.randomUUID().toString().substring(0, 8);

    run(() -> repository.getOrCreate(parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));
    run(() -> repository.markPolicyTriggered(parentCaseId, groupId));

    // Verify by incrementing - should see policyTriggered=true
    SubCaseGroup result = run(() -> repository.incrementCompleted(parentCaseId, groupId));
    assertThat(result.isPolicyTriggered()).isTrue();
  }

  @Test
  void findByChildCaseId_returnsGroup() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-find-" + UUID.randomUUID().toString().substring(0, 8);
    UUID childCaseId = UUID.randomUUID();

    run(() -> repository.getOrCreate(parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));
    run(() -> repository.registerChild(parentCaseId, groupId, childCaseId));

    Optional<SubCaseGroup> result = run(() -> repository.findByChildCaseId(childCaseId));

    assertThat(result).isPresent();
    assertThat(result.get().getParentCaseId()).isEqualTo(parentCaseId);
    assertThat(result.get().getGroupId()).isEqualTo(groupId);
    assertThat(result.get().getChildCaseIds()).contains(childCaseId);
  }

  @Test
  void findByChildCaseId_returnsEmptyForUnknownChild() {
    UUID unknownChildCaseId = UUID.randomUUID();

    Optional<SubCaseGroup> result = run(() -> repository.findByChildCaseId(unknownChildCaseId));

    assertThat(result).isEmpty();
  }

  @Test
  void findByChildCaseId_returnsFirstGroupWhenChildInMultipleGroups() {
    // Edge case: if a child is registered in multiple groups (should not happen, but testing)
    UUID parentCaseId1 = UUID.randomUUID();
    UUID parentCaseId2 = UUID.randomUUID();
    String groupId1 = "group-multi-1-" + UUID.randomUUID().toString().substring(0, 8);
    String groupId2 = "group-multi-2-" + UUID.randomUUID().toString().substring(0, 8);
    UUID sharedChildCaseId = UUID.randomUUID();

    run(() -> repository.getOrCreate(parentCaseId1, groupId1, 3, 2, OnThresholdReached.KEEP));
    run(() -> repository.getOrCreate(parentCaseId2, groupId2, 3, 2, OnThresholdReached.KEEP));
    run(() -> repository.registerChild(parentCaseId1, groupId1, sharedChildCaseId));
    run(() -> repository.registerChild(parentCaseId2, groupId2, sharedChildCaseId));

    Optional<SubCaseGroup> result = run(() -> repository.findByChildCaseId(sharedChildCaseId));

    // Should return one of the groups (implementation returns first match)
    assertThat(result).isPresent();
    assertThat(result.get().getChildCaseIds()).contains(sharedChildCaseId);
  }

  @Test
  void roundTrip_createRegisterIncrementFind() {
    UUID parentCaseId = UUID.randomUUID();
    String groupId = "group-roundtrip-" + UUID.randomUUID().toString().substring(0, 8);
    UUID child1 = UUID.randomUUID();
    UUID child2 = UUID.randomUUID();

    // Create
    SubCaseGroup created =
        run(() -> repository.getOrCreate(parentCaseId, groupId, 5, 3, OnThresholdReached.CANCEL));
    assertThat(created.getInstanceCount()).isEqualTo(5);
    assertThat(created.getRequiredCount()).isEqualTo(3);

    // Register children
    run(() -> repository.registerChild(parentCaseId, groupId, child1));
    run(() -> repository.registerChild(parentCaseId, groupId, child2));

    // Increment counters
    run(() -> repository.incrementCompleted(parentCaseId, groupId));
    run(() -> repository.incrementRejected(parentCaseId, groupId));

    // Mark policy
    Boolean policyResult = run(() -> repository.markPolicyTriggered(parentCaseId, groupId));
    assertThat(policyResult).isTrue();

    // Find by child
    Optional<SubCaseGroup> found = run(() -> repository.findByChildCaseId(child1));
    assertThat(found).isPresent();
    assertThat(found.get().getParentCaseId()).isEqualTo(parentCaseId);
    assertThat(found.get().getGroupId()).isEqualTo(groupId);
    assertThat(found.get().getChildCaseIds()).containsExactlyInAnyOrder(child1, child2);
    assertThat(found.get().getCompletedCount()).isEqualTo(1);
    assertThat(found.get().getRejectedCount()).isEqualTo(1);
    assertThat(found.get().isPolicyTriggered()).isTrue();
    assertThat(found.get().getOnThresholdReached()).isEqualTo(OnThresholdReached.CANCEL);
  }

  private <T> T run(Supplier<Uni<T>> supplier) {
    try {
      return VertxContextSupport.subscribeAndAwait(supplier);
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }
}
