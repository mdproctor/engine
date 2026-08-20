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
package io.casehub.api.model.converter;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.OutcomeType;
import io.casehub.api.model.RecoveryLevel;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperRecoveryTest {

  @Test
  void parsesRecoveryPolicyFromYaml() throws IOException {
    var def =
        CaseDefinitionYamlMapper.load(
            getClass().getClassLoader().getResourceAsStream("recovery-policy-test.yaml"));
    var policy = def.getRecoveryPolicy();
    assertThat(policy).isNotNull();
    assertThat(policy.maxRetries()).isEqualTo(5);
    assertThat(policy.maxRerouteAttempts()).isEqualTo(4);
    assertThat(policy.classifierId()).isEqualTo("heuristic");
    assertThat(policy.enabled()).isTrue();
  }

  @Test
  void parsesRecoveryOverrideFromYaml() throws IOException {
    var def =
        CaseDefinitionYamlMapper.load(
            getClass().getClassLoader().getResourceAsStream("recovery-policy-test.yaml"));
    var binding =
        def.getBindings().stream()
            .filter(b -> "process-binding".equals(b.getName()))
            .findFirst()
            .orElseThrow();
    var override = binding.getRecoveryOverride();
    assertThat(override).isNotNull();
    assertThat(override.maxRetries()).isEqualTo(2);
    assertThat(override.effectiveMaxLevel()).isEqualTo(RecoveryLevel.REASONING);
    assertThat(override.skipRecovery()).isFalse();
    assertThat(override.skipRecoveryFor()).containsExactly(OutcomeType.EXPIRED);
    assertThat(binding.getSideEffectClassification())
        .isEqualTo(io.casehub.api.model.SideEffectClassification.NON_IDEMPOTENT);
  }

  @Test
  void noRecoveryPolicyParsesAsNull() throws IOException {
    var def =
        CaseDefinitionYamlMapper.load(
            getClass().getClassLoader().getResourceAsStream("casehub/minimal.yaml"));
    assertThat(def.getRecoveryPolicy()).isNull();
  }
}
