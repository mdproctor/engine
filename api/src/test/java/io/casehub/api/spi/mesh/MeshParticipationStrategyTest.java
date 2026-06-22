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
package io.casehub.api.spi.mesh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.casehub.api.spi.mesh.MeshParticipationStrategy.MeshParticipation;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MeshParticipationStrategyTest {

  // ── Happy path — behavioral tests use a real UUID ─────────────────────

  @Test
  void active_returnsActive() {
    assertThat(new ActiveParticipationStrategy().strategyFor("worker-1", UUID.randomUUID()))
        .isEqualTo(MeshParticipation.ACTIVE);
  }

  @Test
  void reactive_returnsReactive() {
    assertThat(new ReactiveParticipationStrategy().strategyFor("worker-1", UUID.randomUUID()))
        .isEqualTo(MeshParticipation.REACTIVE);
  }

  @Test
  void silent_returnsSilent() {
    assertThat(new SilentParticipationStrategy().strategyFor("worker-1", UUID.randomUUID()))
        .isEqualTo(MeshParticipation.SILENT);
  }

  @Test
  void threeDistinctValues() {
    UUID id = UUID.randomUUID();
    assertThat(new ActiveParticipationStrategy().strategyFor("w", id))
        .isNotEqualTo(new ReactiveParticipationStrategy().strategyFor("w", id));
    assertThat(new ReactiveParticipationStrategy().strategyFor("w", id))
        .isNotEqualTo(new SilentParticipationStrategy().strategyFor("w", id));
  }

  // ── Null-safety — null caseId is semantically valid (strategy called before case exists) ──

  @Test
  void allStrategiesAcceptNullCaseId() {
    // null caseId is valid: strategy is consulted before buildContext() completes
    assertThatNoException()
        .isThrownBy(
            () -> {
              new ActiveParticipationStrategy().strategyFor("w", null);
              new ReactiveParticipationStrategy().strategyFor("w", null);
              new SilentParticipationStrategy().strategyFor("w", null);
            });
  }

  @Test
  void allStrategiesAcceptNullWorkerId() {
    assertThatNoException()
        .isThrownBy(
            () -> {
              new ActiveParticipationStrategy().strategyFor(null, null);
              new ReactiveParticipationStrategy().strategyFor(null, null);
              new SilentParticipationStrategy().strategyFor(null, null);
            });
  }

  @Test
  void allStrategiesAcceptEmptyWorkerId() {
    assertThatNoException()
        .isThrownBy(
            () -> {
              new ActiveParticipationStrategy().strategyFor("", UUID.randomUUID());
              new ReactiveParticipationStrategy().strategyFor("", UUID.randomUUID());
              new SilentParticipationStrategy().strategyFor("", UUID.randomUUID());
            });
  }

  // ── Correctness ───────────────────────────────────────────────────────

  @Test
  void allStrategiesIgnoreWorkerId() {
    UUID id = UUID.randomUUID();
    var active = new ActiveParticipationStrategy();
    assertThat(active.strategyFor("alice", id)).isEqualTo(active.strategyFor("bob", id));

    var reactive = new ReactiveParticipationStrategy();
    assertThat(reactive.strategyFor("alice", id)).isEqualTo(reactive.strategyFor("bob", id));

    var silent = new SilentParticipationStrategy();
    assertThat(silent.strategyFor("alice", id)).isEqualTo(silent.strategyFor("bob", id));
  }

  @Test
  void resultsAreConsistentAcrossRepeatedCalls() {
    var strategy = new ActiveParticipationStrategy();
    UUID id = UUID.randomUUID();
    MeshParticipation first = strategy.strategyFor("w", id);
    MeshParticipation second = strategy.strategyFor("w", id);
    assertThat(first).isEqualTo(second);
  }

  // Note: participationEnumHasExactlyThreeValues, allThreeEnumValuesAreDistinct, and
  // enumNameMatchesExpectedStrings were removed — they tested Java language semantics
  // (Enum.values().length, Enum.name()) rather than application behaviour. The named()
  // factory tests below verify all three values are reachable and return correct behaviour.
  // See engine#554 for the cleanup rationale.

  // ── named() factory — behavioral tests ───────────────────────────────

  @Test
  void named_active_returnsActive() {
    UUID id = UUID.randomUUID();
    assertThat(MeshParticipationStrategy.named("active").strategyFor("w", id))
        .isEqualTo(MeshParticipation.ACTIVE);
  }

  @Test
  void named_reactive_returnsReactive() {
    UUID id = UUID.randomUUID();
    assertThat(MeshParticipationStrategy.named("reactive").strategyFor("w", id))
        .isEqualTo(MeshParticipation.REACTIVE);
  }

  @Test
  void named_silent_returnsSilent() {
    UUID id = UUID.randomUUID();
    assertThat(MeshParticipationStrategy.named("silent").strategyFor("w", id))
        .isEqualTo(MeshParticipation.SILENT);
  }

  @Test
  void named_unknown_throwsIllegalArgumentException() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MeshParticipationStrategy.named("unknown-strategy"));
  }

  @Test
  void named_null_throwsIllegalArgumentException() {
    assertThatIllegalArgumentException().isThrownBy(() -> MeshParticipationStrategy.named(null));
  }
}
