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

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class StigmergyConfigTest {

  @Test
  void defaultsRecordStoresAllFields() {
    var defaults =
        new StigmergyDefaults(
            Duration.ofMinutes(5),
            0.01,
            100,
            20,
            50,
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            10000,
            50000);
    assertEquals(Duration.ofMinutes(5), defaults.signalHalfLife());
    assertEquals(0.01, defaults.effectiveZeroThreshold());
    assertEquals(100, defaults.maxSignalsPerCase());
    assertEquals(20, defaults.maxObserversPerCase());
    assertEquals(50, defaults.maxRulesPerCase());
    assertEquals(Duration.ofSeconds(60), defaults.rateWindow());
    assertEquals(Duration.ofSeconds(30), defaults.stabilityWindow());
    assertEquals(10000, defaults.maxDispatches());
    assertEquals(50000, defaults.maxEvaluationCycles());
  }

  @Test
  void allNullableFieldsAllowNull() {
    var defaults = new StigmergyDefaults(null, null, null, null, null, null, null, null, null);
    assertNull(defaults.signalHalfLife());
  }

  @Test
  void coordinationConfigHasDefaults() {
    var coord = new CoordinationConfig(2, 10.0, 0.6);
    assertEquals(2, coord.consensusThreshold());
    assertEquals(10.0, coord.stormRateMultiplier());
    assertEquals(0.6, coord.interestHotspotThreshold());
  }

  @Test
  void stigmergyConfigComposesSubRecords() {
    var defaults = new StigmergyDefaults(null, null, null, null, null, null, null, null, null);
    var coord = new CoordinationConfig(null, null, null);
    var config = new StigmergyConfig(defaults, coord, null);
    assertNotNull(config.defaults());
    assertNotNull(config.coordination());
  }

  @Test
  void stigmergyConfigAllowsNullSubRecords() {
    var config = new StigmergyConfig(null, null, null);
    assertNull(config.defaults());
    assertNull(config.coordination());
  }
}
