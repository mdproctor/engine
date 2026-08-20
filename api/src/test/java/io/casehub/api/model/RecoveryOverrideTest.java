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

import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoveryOverrideTest {

  @Test
  void effectiveMaxRetriesUsesOverrideWhenPresent() {
    var override = new RecoveryOverride(5, null, null, false, Set.of());
    assertThat(override.effectiveMaxRetries(RecoveryPolicy.DEFAULT)).isEqualTo(5);
  }

  @Test
  void effectiveMaxRetriesFallsThroughToPolicy() {
    var override = new RecoveryOverride(null, null, null, false, Set.of());
    assertThat(override.effectiveMaxRetries(RecoveryPolicy.DEFAULT)).isEqualTo(3);
  }

  @Test
  void effectiveMaxRerouteUsesOverrideWhenPresent() {
    var override = new RecoveryOverride(null, 10, null, false, Set.of());
    assertThat(override.effectiveMaxRerouteAttempts(RecoveryPolicy.DEFAULT)).isEqualTo(10);
  }

  @Test
  void effectiveMaxLevelDefaultsToFundamental() {
    var override = new RecoveryOverride(null, null, null, false, Set.of());
    assertThat(override.effectiveMaxLevel()).isEqualTo(RecoveryLevel.FUNDAMENTAL);
  }

  @Test
  void effectiveMaxLevelUsesOverrideWhenPresent() {
    var override = new RecoveryOverride(null, null, RecoveryLevel.TRANSIENT, false, Set.of());
    assertThat(override.effectiveMaxLevel()).isEqualTo(RecoveryLevel.TRANSIENT);
  }

  @Test
  void skipFactoryCreatesSkipOverride() {
    var override = RecoveryOverride.skip();
    assertThat(override.skipRecovery()).isTrue();
  }

  @Test
  void skipRecoveryForDefaultsToEmptySet() {
    var override = new RecoveryOverride(null, null, null, false, null);
    assertThat(override.skipRecoveryFor()).isEmpty();
  }

  @Test
  void skipRecoveryForPreservesValues() {
    var override = new RecoveryOverride(null, null, null, false, Set.of(OutcomeType.DECLINED));
    assertThat(override.skipRecoveryFor()).containsExactly(OutcomeType.DECLINED);
  }
}
