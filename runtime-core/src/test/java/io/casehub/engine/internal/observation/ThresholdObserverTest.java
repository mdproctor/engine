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

class ThresholdObserverTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void detectsThresholdCrossing() {
    var observer = ThresholdObserver.of("temperature", ThresholdObserver.Operator.GT, 100.0);
    var snapshot = MAPPER.createObjectNode().put("temperature", 105.0);
    var ctx =
        new ObservationContext(
            snapshot, Set.of("temperature"), List.of(), "agent-1", "t1", UUID.randomUUID());
    var observations = observer.observe(ctx);
    assertEquals(1, observations.size());
    assertEquals("threshold-crossing", observations.get(0).patternId());
    assertEquals(1.0, observations.get(0).confidence());
  }

  @Test
  void noObservationWhenBelowThreshold() {
    var observer = ThresholdObserver.of("temperature", ThresholdObserver.Operator.GT, 100.0);
    var snapshot = MAPPER.createObjectNode().put("temperature", 50.0);
    var ctx =
        new ObservationContext(
            snapshot, Set.of("temperature"), List.of(), "agent-1", "t1", UUID.randomUUID());
    assertTrue(observer.observe(ctx).isEmpty());
  }

  @Test
  void watchedKeysContainsOnlyTargetKey() {
    var observer = ThresholdObserver.of("temperature", ThresholdObserver.Operator.GT, 100.0);
    assertEquals(Set.of("temperature"), observer.watchedKeys());
  }

  @Test
  void handlesNonNumericValueGracefully() {
    var observer = ThresholdObserver.of("temperature", ThresholdObserver.Operator.GT, 100.0);
    var snapshot = MAPPER.createObjectNode().put("temperature", "not-a-number");
    var ctx =
        new ObservationContext(
            snapshot, Set.of("temperature"), List.of(), "agent-1", "t1", UUID.randomUUID());
    assertTrue(observer.observe(ctx).isEmpty());
  }

  @Test
  void handlesMissingKeyGracefully() {
    var observer = ThresholdObserver.of("temperature", ThresholdObserver.Operator.GT, 100.0);
    var snapshot = MAPPER.createObjectNode().put("humidity", 80.0);
    var ctx =
        new ObservationContext(
            snapshot, Set.of("humidity"), List.of(), "agent-1", "t1", UUID.randomUUID());
    assertTrue(observer.observe(ctx).isEmpty());
  }

  @Test
  void lessThanOperator() {
    var observer = ThresholdObserver.of("score", ThresholdObserver.Operator.LT, 50.0);
    var snapshot = MAPPER.createObjectNode().put("score", 30.0);
    var ctx =
        new ObservationContext(
            snapshot, Set.of("score"), List.of(), "agent-1", "t1", UUID.randomUUID());
    assertEquals(1, observer.observe(ctx).size());
  }

  @Test
  void equalOperator() {
    var observer = ThresholdObserver.of("level", ThresholdObserver.Operator.EQ, 5.0);
    var snapshot = MAPPER.createObjectNode().put("level", 5.0);
    var ctx =
        new ObservationContext(
            snapshot, Set.of("level"), List.of(), "agent-1", "t1", UUID.randomUUID());
    assertEquals(1, observer.observe(ctx).size());
  }

  @Test
  void observerTypeIsThreshold() {
    assertEquals(
        "threshold", ThresholdObserver.of("k", ThresholdObserver.Operator.GT, 0).observerType());
  }
}
