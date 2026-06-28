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
package io.casehub.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MilestoneParentStageTest {

  @Test
  void milestone_with_parent_stage_id() {
    var milestone =
        Milestone.builder()
            .name("doc-check")
            .completionCriteria(".docsReceived == true")
            .parentStageId("kyc-stage")
            .build();
    assertThat(milestone.getParentStageId()).isEqualTo("kyc-stage");
  }

  @Test
  void milestone_without_parent_stage_id_returns_null() {
    var milestone =
        Milestone.builder().name("doc-check").completionCriteria(".docsReceived == true").build();
    assertThat(milestone.getParentStageId()).isNull();
  }

  @Test
  void milestone_equality_includes_parent_stage_id() {
    var m1 =
        Milestone.builder()
            .name("doc-check")
            .completionCriteria(".docsReceived == true")
            .parentStageId("stage-a")
            .build();
    var m2 =
        Milestone.builder()
            .name("doc-check")
            .completionCriteria(".docsReceived == true")
            .parentStageId("stage-b")
            .build();
    assertThat(m1).isNotEqualTo(m2);
  }

  @Test
  void milestone_equality_same_parent_stage_id() {
    var m1 =
        Milestone.builder()
            .name("doc-check")
            .completionCriteria(".docsReceived == true")
            .parentStageId("stage-a")
            .build();
    var m2 =
        Milestone.builder()
            .name("doc-check")
            .completionCriteria(".docsReceived == true")
            .parentStageId("stage-a")
            .build();
    assertThat(m1).isEqualTo(m2);
    assertThat(m1.hashCode()).isEqualTo(m2.hashCode());
  }
}
