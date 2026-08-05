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
package io.casehub.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ReflectionTriggerConfigTest {

  @Test
  void defaults_returnsExpectedValues() {
    var config = ReflectionTriggerConfig.defaults();
    assertFalse(config.enabled());
    assertEquals(3.0, config.importanceThreshold());
    assertEquals(10, config.maxUnreflectedOutcomes());
    assertEquals(50, config.maxSourceMemories());
    assertEquals(0.3, config.importanceWeights().get("SUCCESS"));
    assertEquals(0.3, config.importanceWeights().get("COMPLETED"));
    assertEquals(0.6, config.importanceWeights().get("DECLINED"));
    assertEquals(0.8, config.importanceWeights().get("FAILED"));
    assertEquals(0.5, config.importanceWeights().get("EXPIRED"));
  }

  @Test
  void rejectsNegativeThreshold() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReflectionTriggerConfig(true, -1.0, 10, 50, Map.of()));
  }

  @Test
  void rejectsThresholdAboveTen() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReflectionTriggerConfig(true, 11.0, 10, 50, Map.of()));
  }

  @Test
  void rejectsZeroMaxUnreflectedOutcomes() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReflectionTriggerConfig(true, 3.0, 0, 50, Map.of()));
  }

  @Test
  void rejectsZeroMaxSourceMemories() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReflectionTriggerConfig(true, 3.0, 10, 0, Map.of()));
  }

  @Test
  void importanceWeightsAreImmutable() {
    var weights = new java.util.HashMap<>(Map.of("SUCCESS", 0.5));
    var config = new ReflectionTriggerConfig(true, 3.0, 10, 50, weights);
    assertThrows(
        UnsupportedOperationException.class, () -> config.importanceWeights().put("NEW", 0.1));
  }

  @Test
  void nullImportanceWeightsDefaultsToEmpty() {
    var config = new ReflectionTriggerConfig(true, 3.0, 10, 50, null);
    assertTrue(config.importanceWeights().isEmpty());
  }
}
