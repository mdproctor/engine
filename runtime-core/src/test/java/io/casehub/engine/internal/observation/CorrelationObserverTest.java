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
package io.casehub.engine.internal.observation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.spi.observation.ObservationContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorrelationObserverTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void detectsCorrelation() {
    var observer =
        CorrelationObserver.of(
            Set.of("temperature", "humidity"), ".temperature > 30 and .humidity > 80");
    var snapshot = MAPPER.createObjectNode().put("temperature", 35).put("humidity", 85);
    var ctx =
        new ObservationContext(
            snapshot,
            Set.of("temperature", "humidity"),
            List.of(),
            "agent-1",
            "t1",
            UUID.randomUUID());
    var observations = observer.observe(ctx);
    assertEquals(1, observations.size());
    assertEquals("multi-key-correlation", observations.get(0).patternId());
  }

  @Test
  void noObservationWhenConditionFalse() {
    var observer =
        CorrelationObserver.of(
            Set.of("temperature", "humidity"), ".temperature > 30 and .humidity > 80");
    var snapshot = MAPPER.createObjectNode().put("temperature", 20).put("humidity", 85);
    var ctx =
        new ObservationContext(
            snapshot, Set.of("temperature"), List.of(), "agent-1", "t1", UUID.randomUUID());
    assertTrue(observer.observe(ctx).isEmpty());
  }

  @Test
  void watchedKeysMatchDeclaredKeys() {
    var observer = CorrelationObserver.of(Set.of("a", "b"), ".a == .b");
    assertEquals(Set.of("a", "b"), observer.watchedKeys());
  }

  @Test
  void observerTypeIsCorrelation() {
    assertEquals("correlation", CorrelationObserver.of(Set.of("a"), ".a > 0").observerType());
  }

  @Test
  void invalidJqConditionRejectedAtConstruction() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CorrelationObserver.of(Set.of("a"), "invalid jq [[["));
  }
}
