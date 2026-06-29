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
package io.casehub.engine.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CallableDispatchRegistryTest {

  private CallableDispatchRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new CallableDispatchRegistry();
  }

  @Test
  void register_and_get_returns_dispatcher() {
    final CallableDispatcher dispatcher =
        (id, args) -> CompletableFuture.completedFuture(Map.of("ok", true));
    registry.register("test:dispatch", dispatcher);

    assertThat(registry.get("test:dispatch")).isSameAs(dispatcher);
  }

  @Test
  void get_unknown_call_name_throws() {
    assertThatThrownBy(() -> registry.get("unknown:dispatch"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("unknown:dispatch");
  }

  @Test
  void duplicate_registration_throws() {
    final CallableDispatcher dispatcher = (id, args) -> CompletableFuture.completedFuture(Map.of());
    registry.register("dup:dispatch", dispatcher);

    assertThatThrownBy(() -> registry.register("dup:dispatch", dispatcher))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dup:dispatch");
  }

  @Test
  void canHandle_returns_true_for_registered() {
    registry.register("known:dispatch", (id, args) -> CompletableFuture.completedFuture(Map.of()));

    assertThat(registry.canHandle("known:dispatch")).isTrue();
  }

  @Test
  void canHandle_returns_false_for_unregistered() {
    assertThat(registry.canHandle("nope:dispatch")).isFalse();
  }
}
