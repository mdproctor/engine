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

class RecoveryPolicyTest {

  @Test
  void defaultPolicyHasExpectedValues() {
    RecoveryPolicy policy = RecoveryPolicy.DEFAULT;
    assertThat(policy.maxRetries()).isEqualTo(3);
    assertThat(policy.maxRerouteAttempts()).isEqualTo(3);
    assertThat(policy.classifierId()).isEqualTo("heuristic");
    assertThat(policy.revisionStrategyId()).isEqualTo("forward-replan");
    assertThat(policy.replanStrategyId()).isEqualTo("llm");
    assertThat(policy.enabled()).isTrue();
  }

  @Test
  void disabledPolicyIsNotEnabled() {
    RecoveryPolicy policy = RecoveryPolicy.DISABLED;
    assertThat(policy.enabled()).isFalse();
    assertThat(policy.maxRetries()).isZero();
    assertThat(policy.maxRerouteAttempts()).isZero();
  }
}
