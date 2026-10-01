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

import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import io.casehub.api.spi.improvement.CapabilityArea;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CapabilityAreaRegistryTest {

  private CapabilityAreaRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new CapabilityAreaRegistry();
  }

  @Test
  void registerAndRetrieve() {
    registry.register(stubArea("stability"));
    assertThat(registry.active()).hasSize(1);
    assertThat(registry.get("stability")).isPresent();
  }

  @Test
  void deprecateRemovesFromActive() {
    registry.register(stubArea("stability"));
    registry.deprecate("stability");
    assertThat(registry.active()).isEmpty();
  }

  @Test
  void getUnknownReturnsEmpty() {
    assertThat(registry.get("nonexistent")).isEmpty();
  }

  @Test
  void resetClearsRegistry() {
    registry.register(stubArea("stability"));
    registry.reset();
    assertThat(registry.active()).isEmpty();
  }

  private CapabilityArea stubArea(String id) {
    return new CapabilityArea() {
      public String id() {
        return id;
      }

      public String name() {
        return id;
      }

      public String description() {
        return "test";
      }

      public CapabilityAreaAssessment assess(UUID caseId, String tenancyId) {
        return new CapabilityAreaAssessment(
            id,
            0.8,
            CapabilityAreaAssessment.LandscapePosition.AT_PARITY,
            0.5,
            0.3,
            1.67,
            Instant.now());
      }
    };
  }
}
