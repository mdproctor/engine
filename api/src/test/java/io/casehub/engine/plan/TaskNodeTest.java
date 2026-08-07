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
package io.casehub.engine.plan;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TaskNodeTest {

  @Test
  void compoundTaskCarriesId() {
    var compound = new TaskNode.CompoundTask<String>("ct-1", "analysis", List.of());
    assertThat(compound.id()).isEqualTo("ct-1");
    assertThat(compound.name()).isEqualTo("analysis");
  }

  @Test
  void compoundTaskRejectsNullId() {
    assertThatThrownBy(() -> new TaskNode.CompoundTask<>(null, "name", List.of()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void compoundTaskRejectsNullName() {
    assertThatThrownBy(() -> new TaskNode.CompoundTask<>("id", null, List.of()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void compoundTaskDefensiveCopiesMethods() {
    var methods = new java.util.ArrayList<DecompositionMethod<String>>();
    var compound = new TaskNode.CompoundTask<>("ct-1", "analysis", methods);
    methods.add(new DecompositionMethod<>(s -> true, null, null));
    assertThat(compound.methods()).isEmpty();
  }

  @Test
  void decompositionMethodCarriesGuardLabel() {
    var method = new DecompositionMethod<String>(s -> true, null, "when input > threshold");
    assertThat(method.guardLabel()).isEqualTo("when input > threshold");
  }

  @Test
  void decompositionMethodAllowsNullGuardLabel() {
    var method = new DecompositionMethod<String>(s -> true, null, null);
    assertThat(method.guardLabel()).isNull();
  }
}
