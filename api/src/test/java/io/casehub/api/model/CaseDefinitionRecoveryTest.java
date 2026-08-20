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

import io.casehub.worker.api.Capability;
import org.junit.jupiter.api.Test;

class CaseDefinitionRecoveryTest {

  @Test
  void recoveryPolicySetOnDefinition() {
    var policy = RecoveryPolicy.DEFAULT;
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("recovery")
            .version("1.0")
            .recoveryPolicy(policy)
            .build();
    assertThat(def.getRecoveryPolicy()).isEqualTo(policy);
  }

  @Test
  void recoveryPolicyNullByDefault() {
    var def = CaseDefinition.builder().namespace("test").name("recovery").version("1.0").build();
    assertThat(def.getRecoveryPolicy()).isNull();
  }

  @Test
  void recoveryOverrideSetOnBinding() {
    var override = RecoveryOverride.skip();
    var binding =
        Binding.builder()
            .name("b1")
            .capability(Capability.of("cap1", ".", "."))
            .on(new ContextChangeTrigger(".ready"))
            .recoveryOverride(override)
            .build();
    assertThat(binding.getRecoveryOverride()).isEqualTo(override);
  }

  @Test
  void recoveryOverrideNullByDefault() {
    var binding =
        Binding.builder()
            .name("b1")
            .capability(Capability.of("cap1", ".", "."))
            .on(new ContextChangeTrigger(".ready"))
            .build();
    assertThat(binding.getRecoveryOverride()).isNull();
  }
}
