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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapabilityAreaBootstrapTest {

  @Test
  void registersAllDiscoveredAreas() {
    var registry = new CapabilityAreaRegistry();
    var area1 = testArea("area-1");
    var area2 = testArea("area-2");
    var area3 = testArea("area-3");

    var bootstrap = new CapabilityAreaBootstrap();
    bootstrap.registry = registry;

    for (CapabilityArea area : List.of(area1, area2, area3)) {
      registry.register(area);
    }

    assertThat(registry.active()).hasSize(3);
    assertThat(registry.get("area-1")).isPresent();
    assertThat(registry.get("area-2")).isPresent();
    assertThat(registry.get("area-3")).isPresent();
  }

  @Test
  void emptyDiscoveryRegistersNothing() {
    var registry = new CapabilityAreaRegistry();
    assertThat(registry.active()).isEmpty();
  }

  private CapabilityArea testArea(String id) {
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
            0.5,
            CapabilityAreaAssessment.LandscapePosition.AT_PARITY,
            0.0,
            0.0,
            0.0,
            Instant.now());
      }
    };
  }
}
