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
import java.util.Set;
import org.junit.jupiter.api.Test;

class BindingRequiredKeysTest {

  @Test
  void requiredKeys_roundTrip() {
    Binding binding =
        Binding.builder()
            .name("fraud-check")
            .capability(Capability.of("fraud.check", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .requiredKeys(Set.of("entityResolution", "transactionAmount"))
            .build();

    assertThat(binding.getRequiredKeys())
        .containsExactlyInAnyOrder("entityResolution", "transactionAmount");
  }

  @Test
  void requiredKeys_defaultsToNull() {
    Binding binding =
        Binding.builder()
            .name("simple")
            .capability(Capability.of("simple.run", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .build();

    assertThat(binding.getRequiredKeys()).isNull();
  }
}
