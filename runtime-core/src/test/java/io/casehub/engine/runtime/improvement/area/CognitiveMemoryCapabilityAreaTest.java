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
package io.casehub.engine.internal.improvement.area;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.stigmergy.CapabilityAreaAssessment.LandscapePosition;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CognitiveMemoryCapabilityAreaTest {

  private final CognitiveMemoryCapabilityArea area = new CognitiveMemoryCapabilityArea();

  @Test
  void alwaysReturnsNeutral() {
    var assessment = area.assess(UUID.randomUUID(), "test-tenant");
    assertThat(assessment.healthScore()).isEqualTo(0.5);
    assertThat(assessment.landscapePosition()).isEqualTo(LandscapePosition.ABSENT);
  }

  @Test
  void idIsCognitiveMemory() {
    assertThat(area.id()).isEqualTo("cognitive-memory");
  }
}
