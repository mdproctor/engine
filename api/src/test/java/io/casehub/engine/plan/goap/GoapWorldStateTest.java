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
package io.casehub.engine.plan.goap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GoapWorldStateTest {

  @Test
  void satisfiesAll_all_present() {
    var state = new GoapWorldState(Map.of("a", true, "b", true, "c", true));
    assertThat(state.satisfiesAll(Set.of("a", "b"))).isTrue();
  }

  @Test
  void satisfiesAll_one_missing() {
    var state = new GoapWorldState(Map.of("a", true));
    assertThat(state.satisfiesAll(Set.of("a", "b"))).isFalse();
  }

  @Test
  void satisfiesAll_empty_goals() {
    var state = new GoapWorldState(Map.of());
    assertThat(state.satisfiesAll(Set.of())).isTrue();
  }
}
