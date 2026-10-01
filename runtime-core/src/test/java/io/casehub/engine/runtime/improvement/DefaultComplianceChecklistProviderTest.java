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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.improvement.ComplianceChecklist.CheckRequirement.CheckType;
import io.casehub.api.model.improvement.ComplianceLevel;
import org.junit.jupiter.api.Test;

class DefaultComplianceChecklistProviderTest {

  private final DefaultComplianceChecklistProvider provider =
      new DefaultComplianceChecklistProvider();

  @Test
  void l0_hasNoRequirements() {
    var checklist = provider.checklistFor("stability", ComplianceLevel.L0_INERT);
    assertThat(checklist.requirements()).isEmpty();
  }

  @Test
  void l1_hasInfrastructureAndDataFlow() {
    var checklist = provider.checklistFor("stability", ComplianceLevel.L1_OBSERVE);
    assertThat(checklist.requirements()).hasSize(3);
    assertThat(checklist.requirements().get(0).name()).isEqualTo("stability-area-registered");
    assertThat(checklist.requirements().get(0).type()).isEqualTo(CheckType.INFRASTRUCTURE);
    assertThat(checklist.requirements().get(1).name()).isEqualTo("stability-has-data");
    assertThat(checklist.requirements().get(1).type()).isEqualTo(CheckType.DATA_FLOW);
    assertThat(checklist.requirements().get(2).name()).isEqualTo("stability-non-neutral-score");
    assertThat(checklist.requirements().get(2).type()).isEqualTo(CheckType.DATA_FLOW);
  }

  @Test
  void l2_addsConfigurationRequirements() {
    var checklist = provider.checklistFor("stability", ComplianceLevel.L2_PROPOSE);
    assertThat(checklist.requirements()).hasSize(5);
    assertThat(checklist.requirements().get(3).name()).isEqualTo("evolution-enabled");
    assertThat(checklist.requirements().get(3).type()).isEqualTo(CheckType.CONFIGURATION);
    assertThat(checklist.requirements().get(4).name()).isEqualTo("consensus-threshold-met");
  }

  @Test
  void l3_addsRollbackAndHealthRequirements() {
    var checklist = provider.checklistFor("stability", ComplianceLevel.L3_AUTONOMOUS);
    assertThat(checklist.requirements()).hasSize(7);
    assertThat(checklist.requirements().get(5).name()).isEqualTo("rollback-policy-configured");
    assertThat(checklist.requirements().get(6).name()).isEqualTo("health-threshold-configured");
  }

  @Test
  void areaIdIsReflectedInRequirementNames() {
    var checklist = provider.checklistFor("performance", ComplianceLevel.L1_OBSERVE);
    assertThat(checklist.requirements().get(0).name()).isEqualTo("performance-area-registered");
    assertThat(checklist.areaId()).isEqualTo("performance");
  }
}
