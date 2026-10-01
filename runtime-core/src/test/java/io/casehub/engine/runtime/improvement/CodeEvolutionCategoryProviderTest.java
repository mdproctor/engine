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
package io.casehub.engine.internal.improvement;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.stigmergy.StageDescriptor;
import org.junit.jupiter.api.Test;

class CodeEvolutionCategoryProviderTest {

  private final CodeEvolutionCategoryProvider provider = new CodeEvolutionCategoryProvider();

  @Test
  void domainIdIsCodeEvolution() {
    assertThat(provider.domainId()).isEqualTo("code-evolution");
  }

  @Test
  void categoriesReturnsFiveEntries() {
    assertThat(provider.categories()).hasSize(5);
    assertThat(provider.categories())
        .extracting("id")
        .containsExactlyInAnyOrder(
            "dependency-update", "lint-fix", "coverage-gap", "ci-triage", "recipe");
  }

  @Test
  void stagesReturnsElevenInOrdinalOrder() {
    var stages = provider.stages();
    assertThat(stages).hasSize(11);
    for (int i = 0; i < stages.size(); i++) {
      assertThat(stages.get(i).ordinal()).isEqualTo(i);
    }
  }

  @Test
  void gateCheckpointsAreCorrect() {
    var checkpoints =
        provider.stages().stream()
            .filter(StageDescriptor::gateCheckpoint)
            .map(StageDescriptor::id)
            .toList();
    assertThat(checkpoints)
        .containsExactlyInAnyOrder(
            "research-scope", "hypothesis-approval", "implementation-plan", "pr-review");
  }

  @Test
  void allStagesHaveCodeEvolutionDomain() {
    assertThat(provider.stages()).allMatch(s -> "code-evolution".equals(s.domainId()));
  }
}
