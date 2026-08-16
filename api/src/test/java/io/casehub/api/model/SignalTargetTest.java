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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignalTargetTest {

  @Test
  void signalTarget_createsImmutablePayload() {
    Map<String, Object> payload = Map.of("caseSla", Map.of("expired", true));
    var target = new SignalTarget(payload);
    assertThat(target.payload()).isEqualTo(payload);
    assertThatThrownBy(() -> target.payload().put("extra", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void signalTarget_rejectsNullPayload() {
    assertThatThrownBy(() -> new SignalTarget(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void signalTarget_rejectsEmptyPayload() {
    assertThatThrownBy(() -> new SignalTarget(Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void binding_signalBuilder_setsTarget() {
    var binding =
        Binding.builder()
            .name("case-timeout")
            .signal(Map.of("caseSla", Map.of("expired", true)))
            .on(ScheduleTrigger.delay(Duration.ofHours(48)))
            .build();
    assertThat(binding.target()).isInstanceOf(SignalTarget.class);
    assertThat(((SignalTarget) binding.target()).payload()).containsKey("caseSla");
  }

  @Test
  void binding_signalTarget_rejectsNonBindingScope() {
    assertThatThrownBy(
            () ->
                Binding.builder()
                    .name("bad")
                    .signal(Map.of("key", "value"))
                    .on(ScheduleTrigger.delay(Duration.ofHours(1)))
                    .lifecycleScope(LifecycleScope.COMPOUND)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BINDING");
  }

  @Test
  void binding_signalTarget_rejectsCompanionParticipation() {
    assertThatThrownBy(
            () ->
                Binding.builder()
                    .name("bad")
                    .signal(Map.of("key", "value"))
                    .on(ScheduleTrigger.delay(Duration.ofHours(1)))
                    .participation(Participation.COMPANION)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("COMPANION");
  }
}
