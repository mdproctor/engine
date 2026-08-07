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
package io.casehub.engine.planning.decomposition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.TaskStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoalStepTest {

  @Test
  void taskDescriptorContract() {
    var id = UUID.randomUUID();
    var now = Instant.now();
    var step = new GoalStep(id, "Gather data", "data-gathering", now);

    assertThat(step.id()).isEqualTo(id.toString());
    assertThat(step.description()).isEqualTo("Gather data");
    assertThat(step.capabilityName()).isEqualTo("data-gathering");
    assertThat(step.status()).isEqualTo(TaskStatus.PENDING);
    assertThat(step.createdAt()).isEqualTo(now);
    assertThat(step.executor()).isNull();
  }

  @Test
  void snapshotDelegatesToDefault() {
    var step = new GoalStep(UUID.randomUUID(), "Analyse", "analysis", Instant.now());
    var snapshot = step.snapshot();

    assertThat(snapshot.description()).isEqualTo("Analyse");
    assertThat(snapshot.status()).isEqualTo(TaskStatus.PENDING);
    assertThat(snapshot.executorName()).isNull();
  }

  @Test
  void rejectsNullCapabilityName() {
    assertThatThrownBy(() -> new GoalStep(UUID.randomUUID(), "desc", null, Instant.now()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsNullDescription() {
    assertThatThrownBy(() -> new GoalStep(UUID.randomUUID(), null, "cap", Instant.now()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsNullId() {
    assertThatThrownBy(() -> new GoalStep(null, "desc", "cap", Instant.now()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void goalStepIdAccessible() {
    var id = UUID.randomUUID();
    var step = new GoalStep(id, "desc", "cap", Instant.now());
    assertThat(step.goalStepId()).isEqualTo(id);
  }
}
