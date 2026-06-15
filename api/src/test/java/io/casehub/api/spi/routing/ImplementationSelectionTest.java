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
package io.casehub.api.spi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImplementationSelectionTest {

  @Test
  void selected_rejects_empty_list() {
    assertThatThrownBy(() -> new ImplementationSelection.Selected(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void selected_single_binding() {
    var s = new ImplementationSelection.Selected(List.of("b1"));
    assertThat(s.bindingNames()).containsExactly("b1");
  }

  @Test
  void selected_subset() {
    var s = new ImplementationSelection.Selected(List.of("b1", "b2"));
    assertThat(s.bindingNames()).containsExactly("b1", "b2");
  }

  @Test
  void selected_defensively_copies_list() {
    var mutable = new ArrayList<>(List.of("b1"));
    var s = new ImplementationSelection.Selected(mutable);
    mutable.add("b2");
    assertThat(s.bindingNames()).containsExactly("b1");
  }

  @Test
  void selected_list_is_immutable() {
    var s = new ImplementationSelection.Selected(List.of("b1"));
    assertThatThrownBy(() -> s.bindingNames().add("b2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
