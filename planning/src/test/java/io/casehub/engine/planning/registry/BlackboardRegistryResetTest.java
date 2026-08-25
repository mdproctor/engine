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
package io.casehub.engine.planning.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.common.spi.Resettable;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BlackboardRegistryResetTest {

  @Test
  void implements_resettable() {
    assertThat(new BlackboardRegistry()).isInstanceOf(Resettable.class);
  }

  @Test
  void reset_clears_all_entries() {
    BlackboardRegistry registry = new BlackboardRegistry();
    UUID case1 = UUID.randomUUID();
    UUID case2 = UUID.randomUUID();

    registry.getOrCreate(case1, "t1");
    registry.getOrCreate(case2, "t2");

    assertThat(registry.get(case1)).isPresent();
    assertThat(registry.get(case2)).isPresent();

    registry.reset();

    assertThat(registry.get(case1)).isEmpty();
    assertThat(registry.get(case2)).isEmpty();
  }
}
