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

class BindingExchangeTest {

  private static final Capability CAP = Capability.of("cap", ".", ".");

  @Test
  void exchangeOnly_setsStrategy() {
    Binding b =
        Binding.builder()
            .name("b")
            .capability(CAP)
            .on(new ContextChangeTrigger(".x != null"))
            .exchangeOnly()
            .build();
    assertThat(b.getExchangeProjectionStrategy()).isEqualTo("exchange-only");
  }

  @Test
  void dualWrite_setsStrategy() {
    Binding b =
        Binding.builder()
            .name("b")
            .capability(CAP)
            .on(new ContextChangeTrigger(".x != null"))
            .dualWrite()
            .build();
    assertThat(b.getExchangeProjectionStrategy()).isEqualTo("dual-write");
  }

  @Test
  void produces_setsChannelName() {
    Binding b =
        Binding.builder()
            .name("b")
            .capability(CAP)
            .on(new ContextChangeTrigger(".x != null"))
            .produces("tx-stream")
            .build();
    assertThat(b.getProduces()).isEqualTo("tx-stream");
  }

  @Test
  void consumes_setsChannelName() {
    Binding b =
        Binding.builder()
            .name("b")
            .capability(CAP)
            .on(new ContextChangeTrigger(".x != null"))
            .consumes("tx-stream")
            .build();
    assertThat(b.getConsumes()).isEqualTo("tx-stream");
  }

  @Test
  void defaultProjectionStrategy_isNull() {
    Binding b =
        Binding.builder()
            .name("b")
            .capability(CAP)
            .on(new ContextChangeTrigger(".x != null"))
            .build();
    assertThat(b.getExchangeProjectionStrategy()).isNull();
    assertThat(b.getProduces()).isNull();
    assertThat(b.getConsumes()).isNull();
  }
}
