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
package io.casehub.blackboard.subcase;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.OnThresholdReached;
import io.casehub.engine.common.internal.model.GroupStatus;
import io.casehub.engine.common.internal.model.SubCaseGroup;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubCaseGroupPolicyTest {

  private SubCaseGroup group(int instanceCount, int requiredCount, int completed, int rejected) {
    SubCaseGroup g = new SubCaseGroup();
    g.setParentCaseId(UUID.randomUUID());
    g.setGroupId("test-group");
    g.setInstanceCount(instanceCount);
    g.setRequiredCount(requiredCount);
    g.setCompletedCount(completed);
    g.setRejectedCount(rejected);
    g.setPolicyTriggered(false);
    g.setOnThresholdReached(OnThresholdReached.KEEP);
    return g;
  }

  @Test
  void allOf_allComplete_returnsCompleted() {
    assertThat(SubCaseGroupPolicy.evaluate(group(3, 3, 3, 0))).isEqualTo(GroupStatus.COMPLETED);
  }

  @Test
  void anyOf_firstCompletes_returnsCompleted() {
    assertThat(SubCaseGroupPolicy.evaluate(group(3, 1, 1, 0))).isEqualTo(GroupStatus.COMPLETED);
  }

  @Test
  void mOfN_mComplete_returnsCompleted() {
    assertThat(SubCaseGroupPolicy.evaluate(group(3, 2, 2, 0))).isEqualTo(GroupStatus.COMPLETED);
  }

  @Test
  void mOfN_belowThreshold_returnsInProgress() {
    assertThat(SubCaseGroupPolicy.evaluate(group(3, 2, 1, 0))).isEqualTo(GroupStatus.IN_PROGRESS);
  }

  @Test
  void rejected_thresholdUnreachable_returnsRejected() {
    // 2 of 3 needed; 2 rejected, 0 remaining → unreachable
    assertThat(SubCaseGroupPolicy.evaluate(group(3, 2, 0, 2))).isEqualTo(GroupStatus.REJECTED);
  }

  @Test
  void policyTriggered_returnsNull() {
    SubCaseGroup g = group(3, 3, 3, 0);
    g.setPolicyTriggered(true);
    assertThat(SubCaseGroupPolicy.evaluate(g)).isNull();
  }

  @Test
  void toEvent_mapsAllFields() {
    SubCaseGroup g = group(3, 2, 2, 1);
    SubCaseGroupLifecycleEvent evt =
        SubCaseGroupPolicy.toEvent(g, GroupStatus.COMPLETED, "tenant-1");
    assertThat(evt.parentCaseId()).isEqualTo(g.getParentCaseId());
    assertThat(evt.tenancyId()).isEqualTo("tenant-1");
    assertThat(evt.groupId()).isEqualTo("test-group");
    assertThat(evt.groupStatus()).isEqualTo(GroupStatus.COMPLETED);
    assertThat(evt.completedCount()).isEqualTo(2);
    assertThat(evt.instanceCount()).isEqualTo(3);
  }

  @Test
  void oneRemaining_oneNeeded_stillInProgress() {
    // 3 total, 2 needed, 1 completed, 1 rejected → 1 remaining, 1 still needed → possible
    assertThat(SubCaseGroupPolicy.evaluate(group(3, 2, 1, 1))).isEqualTo(GroupStatus.IN_PROGRESS);
  }

  @Test
  void zeroRemaining_thresholdNotMet_returnsRejected() {
    // 3 total, 2 needed, 1 completed, 2 rejected → 0 remaining, need 1 more → rejected
    assertThat(SubCaseGroupPolicy.evaluate(group(3, 2, 1, 2))).isEqualTo(GroupStatus.REJECTED);
  }
}
