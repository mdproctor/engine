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

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CapabilityAreaAssessmentTest {

  @Test
  void gapMapSortsByRoiDescending() {
    var ahead =
        new CapabilityAreaAssessment(
            "stability",
            0.9,
            CapabilityAreaAssessment.LandscapePosition.AHEAD,
            0.1,
            0.5,
            0.2,
            Instant.now());
    var behind =
        new CapabilityAreaAssessment(
            "performance",
            0.4,
            CapabilityAreaAssessment.LandscapePosition.BEHIND,
            0.8,
            0.3,
            2.67,
            Instant.now());
    var absent =
        new CapabilityAreaAssessment(
            "autonomy",
            0.2,
            CapabilityAreaAssessment.LandscapePosition.ABSENT,
            0.9,
            0.5,
            1.8,
            Instant.now());
    var map = new GapMap(List.of(ahead, behind, absent), Instant.now());
    var gaps = map.gapsByRoi();
    assertThat(gaps).hasSize(2);
    assertThat(gaps.get(0).areaId()).isEqualTo("performance");
    assertThat(gaps.get(1).areaId()).isEqualTo("autonomy");
  }

  @Test
  void gapMapExcludesAheadPositions() {
    var ahead =
        new CapabilityAreaAssessment(
            "stability",
            0.9,
            CapabilityAreaAssessment.LandscapePosition.AHEAD,
            0.1,
            0.5,
            0.2,
            Instant.now());
    var map = new GapMap(List.of(ahead), Instant.now());
    assertThat(map.gapsByRoi()).isEmpty();
  }

  @Test
  void gapMapIncludesAtParity() {
    var atParity =
        new CapabilityAreaAssessment(
            "execution",
            0.7,
            CapabilityAreaAssessment.LandscapePosition.AT_PARITY,
            0.3,
            0.4,
            0.75,
            Instant.now());
    var map = new GapMap(List.of(atParity), Instant.now());
    assertThat(map.gapsByRoi()).hasSize(1);
    assertThat(map.gapsByRoi().get(0).areaId()).isEqualTo("execution");
  }
}
