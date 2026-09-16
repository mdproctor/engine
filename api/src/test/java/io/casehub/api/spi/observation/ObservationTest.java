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
package io.casehub.api.spi.observation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObservationTest {

  @Test
  void validConfidenceAccepted() {
    var obs =
        new Observation(
            "test-pattern", 0.75, Map.of("key", TextNode.valueOf("val")), Instant.now());
    assertEquals(0.75, obs.confidence());
    assertEquals("test-pattern", obs.patternId());
  }

  @Test
  void confidenceBelowZeroRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new Observation("p", -0.1, Map.of(), Instant.now()));
  }

  @Test
  void confidenceAboveOneRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new Observation("p", 1.1, Map.of(), Instant.now()));
  }

  @Test
  void boundaryConfidenceAccepted() {
    assertDoesNotThrow(() -> new Observation("p", 0.0, Map.of(), Instant.now()));
    assertDoesNotThrow(() -> new Observation("p", 1.0, Map.of(), Instant.now()));
  }
}
