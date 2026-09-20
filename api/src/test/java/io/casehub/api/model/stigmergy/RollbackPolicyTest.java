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
package io.casehub.api.model.stigmergy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RollbackPolicyTest {

  @Test
  void defaultsAreConservative() {
    var policy = new RollbackPolicy(null, null, null, null, null, null);
    assertThat(policy.effectiveAutoRevertThreshold()).isEqualTo(0.9);
    assertThat(policy.effectivePauseThreshold()).isEqualTo(0.5);
    assertThat(policy.effectiveRequireReviewForRevert()).isTrue();
    assertThat(policy.effectivePauseCategoryOnRegression()).isTrue();
    assertThat(policy.effectiveRegressionWindowMinutes()).isEqualTo(60);
    assertThat(policy.effectiveSustainedFailureCount()).isEqualTo(2);
  }

  @Test
  void overridesApply() {
    var policy = new RollbackPolicy(0.8, 0.3, false, false, 120, 5);
    assertThat(policy.effectiveAutoRevertThreshold()).isEqualTo(0.8);
    assertThat(policy.effectivePauseThreshold()).isEqualTo(0.3);
    assertThat(policy.effectiveRequireReviewForRevert()).isFalse();
    assertThat(policy.effectivePauseCategoryOnRegression()).isFalse();
    assertThat(policy.effectiveRegressionWindowMinutes()).isEqualTo(120);
    assertThat(policy.effectiveSustainedFailureCount()).isEqualTo(5);
  }
}
