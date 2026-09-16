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

import java.util.Map;
import org.junit.jupiter.api.Test;

class InterestLandscapeTest {

  @Test
  void emptyLandscape() {
    var landscape = InterestLandscape.EMPTY;
    assertTrue(landscape.keyObserverCounts().isEmpty());
    assertTrue(landscape.signalObserverCounts().isEmpty());
    assertTrue(landscape.interestTypeCounts().isEmpty());
    assertEquals(0, landscape.totalObserverCount());
  }

  @Test
  void populatedLandscape() {
    var landscape =
        new InterestLandscape(
            Map.of("riskScore", 3, "amount", 1),
            Map.of("danger", 2),
            Map.of("threshold", 4, "correlation", 2),
            8);
    assertEquals(3, landscape.keyObserverCounts().get("riskScore"));
    assertEquals(2, landscape.signalObserverCounts().get("danger"));
    assertEquals(8, landscape.totalObserverCount());
  }
}
