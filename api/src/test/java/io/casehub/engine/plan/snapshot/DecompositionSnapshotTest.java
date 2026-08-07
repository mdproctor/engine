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
package io.casehub.engine.plan.snapshot;

import static org.assertj.core.api.Assertions.*;

import io.casehub.engine.plan.DecompositionMethod;
import io.casehub.engine.plan.TaskNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecompositionSnapshotTest {

  @Test
  void fromLeafTaskNode() {
    var leaf = new TestLeafTask("leaf-1", "Analyse", "exec-1");
    var snapshot = DecompositionSnapshot.from(leaf, Instant.now());

    assertThat(snapshot.root()).isInstanceOf(LeafTaskSnapshot.class);
    var leafSnap = (LeafTaskSnapshot) snapshot.root();
    assertThat(leafSnap.id()).isEqualTo("leaf-1");
    assertThat(leafSnap.description()).isEqualTo("Analyse");
    assertThat(leafSnap.executorName()).isEqualTo("exec-1");
  }

  @Test
  void fromCompoundTaskNode() {
    var method = new DecompositionMethod<TestLeafTask>(t -> true, null, "always");
    var compound = new TaskNode.CompoundTask<>("ct-1", "analysis-phase", List.of(method));
    var snapshot = DecompositionSnapshot.from(compound, Instant.now());

    assertThat(snapshot.root()).isInstanceOf(CompoundTaskSnapshot.class);
    var compSnap = (CompoundTaskSnapshot) snapshot.root();
    assertThat(compSnap.id()).isEqualTo("ct-1");
    assertThat(compSnap.name()).isEqualTo("analysis-phase");
    assertThat(compSnap.methods()).hasSize(1);
    assertThat(compSnap.methods().get(0).guardLabel()).isEqualTo("always");
  }

  @Test
  void fromCompoundWithNullGuardLabel() {
    var method = new DecompositionMethod<TestLeafTask>(t -> true, null, null);
    var compound = new TaskNode.CompoundTask<>("ct-2", "phase", List.of(method));
    var snapshot = DecompositionSnapshot.from(compound, Instant.now());

    var compSnap = (CompoundTaskSnapshot) snapshot.root();
    assertThat(compSnap.methods().get(0).guardLabel()).isNull();
    assertThat(compSnap.methods().get(0).strategyId()).isNull();
  }
}
