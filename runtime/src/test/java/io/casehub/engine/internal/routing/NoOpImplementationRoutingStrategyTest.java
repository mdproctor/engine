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
package io.casehub.engine.internal.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.spi.routing.ImplementationCandidate;
import io.casehub.api.spi.routing.ImplementationRoutingContext;
import io.casehub.api.spi.routing.ImplementationSelection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoOpImplementationRoutingStrategyTest {

  private final NoOpImplementationRoutingStrategy strategy =
      new NoOpImplementationRoutingStrategy();

  @Test
  void returns_runAll() {
    var ctx =
        new ImplementationRoutingContext(
            UUID.randomUUID(), "someCapability", null, "test-tenant", List.of());
    var candidates =
        List.of(
            new ImplementationCandidate("b1", "w1", "someCapability"),
            new ImplementationCandidate("b2", "w2", "someCapability"));

    ImplementationSelection result = strategy.select(ctx, candidates);

    assertThat(result).isInstanceOf(ImplementationSelection.RunAll.class);
  }

  @Test
  void id_is_run_all() {
    assertThat(strategy.id()).isEqualTo("run-all");
  }
}
