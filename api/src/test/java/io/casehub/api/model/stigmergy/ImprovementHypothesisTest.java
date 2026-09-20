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

class ImprovementHypothesisTest {

  @Test
  void radarRecommendationValues() {
    assertThat(ImprovementHypothesis.RadarRecommendation.values()).hasSize(3);
    assertThat(ImprovementHypothesis.RadarRecommendation.ADOPT).isNotNull();
    assertThat(ImprovementHypothesis.RadarRecommendation.TRIAL).isNotNull();
    assertThat(ImprovementHypothesis.RadarRecommendation.ASSESS).isNotNull();
  }

  @Test
  void hypothesisRecordCreation() {
    var hypothesis =
        new ImprovementHypothesis(
            "technique",
            "target",
            "expected",
            "evidence",
            "risk",
            "stability",
            ImprovementHypothesis.RadarRecommendation.TRIAL);
    assertThat(hypothesis.technique()).isEqualTo("technique");
    assertThat(hypothesis.capabilityArea()).isEqualTo("stability");
    assertThat(hypothesis.radarRecommendation())
        .isEqualTo(ImprovementHypothesis.RadarRecommendation.TRIAL);
  }
}
