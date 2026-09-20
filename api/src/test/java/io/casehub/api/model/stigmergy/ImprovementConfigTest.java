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
package io.casehub.api.model.stigmergy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImprovementConfigTest {

  @Test
  void backwardsCompatibleConstructor() {
    var config = new ImprovementConfig("improvement", 2, null, null, null);
    assertThat(config.effectiveEvolutionEnabled()).isFalse();
    assertThat(config.effectiveEvolutionTickIntervalMinutes()).isEqualTo(60);
    assertThat(config.effectiveRollbackPolicy()).isNotNull();
    assertThat(config.effectiveHealthPolicy()).isNotNull();
    assertThat(config.effectiveConflictTrivialThreshold()).isEqualTo(10);
  }

  @Test
  void evolutionIsOptIn() {
    var config = new ImprovementConfig(null, null, null, null, null);
    assertThat(config.effectiveEvolutionEnabled()).isFalse();
  }

  @Test
  void fullConstructor() {
    var config =
        new ImprovementConfig(
            "improvement",
            2,
            null,
            null,
            null,
            true,
            30,
            new RollbackPolicy(0.8, null, null, null, null, null),
            new HealthPolicy(null, null, null, null, null, null),
            new ResearchMethodology(null, null, null, null, null, null),
            5);
    assertThat(config.effectiveEvolutionEnabled()).isTrue();
    assertThat(config.effectiveEvolutionTickIntervalMinutes()).isEqualTo(30);
    assertThat(config.effectiveRollbackPolicy().effectiveAutoRevertThreshold()).isEqualTo(0.8);
    assertThat(config.effectiveConflictTrivialThreshold()).isEqualTo(5);
  }
}
