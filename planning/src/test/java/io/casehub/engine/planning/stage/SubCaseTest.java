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
package io.casehub.engine.planning.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.DefaultSubCaseCompletionStrategy;
import io.casehub.api.model.OnThresholdReached;
import io.casehub.api.model.SubCase;
import io.casehub.api.model.SubCaseCompletionStrategy;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubCaseTest {

  @Test
  void namespace_name_version_required() {
    assertThatThrownBy(() -> SubCase.builder().name("n").version("v").build())
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> SubCase.builder().namespace("ns").version("v").build())
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> SubCase.builder().namespace("ns").name("n").build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void fields_retained() {
    SubCase sc = SubCase.builder().namespace("io.casehub").name("loan").version("1.0.0").build();
    assertThat(sc.namespace()).isEqualTo("io.casehub");
    assertThat(sc.name()).isEqualTo("loan");
    assertThat(sc.version()).isEqualTo("1.0.0");
  }

  @Test
  void default_strategy_used_when_not_specified() {
    SubCase sc = SubCase.builder().namespace("ns").name("n").version("v").build();
    assertThat(sc.completionStrategy()).isInstanceOf(DefaultSubCaseCompletionStrategy.class);
  }

  @Test
  void custom_strategy_retained() {
    SubCaseCompletionStrategy custom = s -> SubCaseCompletionStrategy.ItemStatus.COMPLETED;
    SubCase sc =
        SubCase.builder().namespace("ns").name("n").version("v").completionStrategy(custom).build();
    assertThat(sc.completionStrategy()).isSameAs(custom);
  }

  @Test
  void default_strategy_maps_completed_to_completed() {
    DefaultSubCaseCompletionStrategy s = new DefaultSubCaseCompletionStrategy();
    assertThat(s.mapToStageItemStatus(CaseStatus.COMPLETED))
        .isEqualTo(SubCaseCompletionStrategy.ItemStatus.COMPLETED);
  }

  @Test
  void default_strategy_maps_faulted_to_faulted() {
    DefaultSubCaseCompletionStrategy s = new DefaultSubCaseCompletionStrategy();
    assertThat(s.mapToStageItemStatus(CaseStatus.FAULTED))
        .isEqualTo(SubCaseCompletionStrategy.ItemStatus.FAULTED);
  }

  @Test
  void default_strategy_maps_running_to_terminated() {
    DefaultSubCaseCompletionStrategy s = new DefaultSubCaseCompletionStrategy();
    assertThat(s.mapToStageItemStatus(CaseStatus.RUNNING))
        .isEqualTo(SubCaseCompletionStrategy.ItemStatus.TERMINATED);
  }

  @Test
  void subcase_added_to_plan_model() {
    DefaultCasePlanModel plan = new DefaultCasePlanModel(UUID.randomUUID());
    SubCase sc = SubCase.builder().namespace("ns").name("n").version("v").build();
    plan.addSubCase(sc);
    assertThat(plan.getSubCases()).containsExactly(sc);
  }

  @Test
  void builder_defaultWaitForCompletion_isTrue() {
    SubCase sc = SubCase.builder().namespace("ns").name("n").version("1.0").build();
    assertThat(sc.waitForCompletion()).isTrue();
  }

  @Test
  void builder_waitForCompletionFalse_stored() {
    SubCase sc =
        SubCase.builder().namespace("ns").name("n").version("1.0").waitForCompletion(false).build();
    assertThat(sc.waitForCompletion()).isFalse();
  }

  @Test
  void builder_inputMapping_defaultIdentity() {
    SubCase sc = SubCase.builder().namespace("ns").name("n").version("1.0").build();
    assertThat(sc.inputMapping()).isEqualTo(io.casehub.api.model.SubCaseMapping.of("."));
  }

  @Test
  void builder_outputMapping_defaultNull() {
    SubCase sc = SubCase.builder().namespace("ns").name("n").version("1.0").build();
    assertThat(sc.outputMapping()).isNull();
  }

  @Test
  void builder_customMappings_stored() {
    SubCase sc =
        SubCase.builder()
            .namespace("ns")
            .name("n")
            .version("1.0")
            .inputMapping("{ id: .caseId }")
            .outputMapping("{ result: .childResult }")
            .build();
    assertThat(sc.inputMapping())
        .isEqualTo(io.casehub.api.model.SubCaseMapping.of("{ id: .caseId }"));
    assertThat(sc.outputMapping())
        .isEqualTo(io.casehub.api.model.SubCaseMapping.of("{ result: .childResult }"));
  }

  @Test
  void groupId_and_totalInGroup_stored() {
    SubCase sc =
        SubCase.builder()
            .namespace("ns")
            .name("n")
            .version("v")
            .groupId("site-review")
            .totalInGroup(3)
            .requiredCount(2)
            .onThresholdReached(OnThresholdReached.CANCEL)
            .build();
    assertThat(sc.groupId()).isEqualTo("site-review");
    assertThat(sc.totalInGroup()).isEqualTo(3);
    assertThat(sc.requiredCount()).isEqualTo(2);
    assertThat(sc.onThresholdReached()).isEqualTo(OnThresholdReached.CANCEL);
  }

  @Test
  void requiredCount_defaults_to_totalInGroup() {
    SubCase sc =
        SubCase.builder()
            .namespace("ns")
            .name("n")
            .version("v")
            .groupId("g")
            .totalInGroup(3)
            .build();
    assertThat(sc.requiredCount()).isEqualTo(3);
  }

  @Test
  void onThresholdReached_defaults_to_keep() {
    SubCase sc =
        SubCase.builder()
            .namespace("ns")
            .name("n")
            .version("v")
            .groupId("g")
            .totalInGroup(2)
            .build();
    assertThat(sc.onThresholdReached()).isEqualTo(OnThresholdReached.KEEP);
  }
}
