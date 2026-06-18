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

class OutcomePolicyTest {

  @Test
  void default_constructor_all_reroute_max3() {
    OutcomePolicy policy = new OutcomePolicy();
    assertThat(policy.onDecline()).isEqualTo(OutcomeAction.REROUTE);
    assertThat(policy.onFailure()).isEqualTo(OutcomeAction.REROUTE);
    assertThat(policy.onExpired()).isEqualTo(OutcomeAction.REROUTE);
    assertThat(policy.maxRerouteAttempts()).isEqualTo(3);
  }

  @Test
  void custom_policy() {
    OutcomePolicy policy =
        new OutcomePolicy(OutcomeAction.FAULT, OutcomeAction.REROUTE, OutcomeAction.REROUTE, 5);
    assertThat(policy.onDecline()).isEqualTo(OutcomeAction.FAULT);
    assertThat(policy.onFailure()).isEqualTo(OutcomeAction.REROUTE);
    assertThat(policy.maxRerouteAttempts()).isEqualTo(5);
  }

  @Test
  void binding_builder_sets_outcomePolicy() {
    OutcomePolicy policy =
        new OutcomePolicy(OutcomeAction.FAULT, OutcomeAction.FAULT, OutcomeAction.REROUTE, 1);
    Binding binding =
        Binding.builder()
            .name("test")
            .capability(new Capability("cap", null, null))
            .on(new ContextChangeTrigger(null, null))
            .outcomePolicy(policy)
            .build();
    assertThat(binding.getOutcomePolicy()).isEqualTo(policy);
  }

  @Test
  void binding_without_outcomePolicy_returns_null() {
    Binding binding =
        Binding.builder()
            .name("test")
            .capability(new Capability("cap", null, null))
            .on(new ContextChangeTrigger(null, null))
            .build();
    assertThat(binding.getOutcomePolicy()).isNull();
  }
}
