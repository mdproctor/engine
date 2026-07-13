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
package io.casehub.api.spi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.platform.api.preferences.Preferences;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrustRoutingPolicyResolverTest {

  private static final io.casehub.api.spi.routing.TrustRoutingPolicyKeys KEYS =
      io.casehub.api.spi.routing.TrustRoutingPolicyKeys.create("casehubio.test.trust-routing")
          .withFloor("accuracy", "accuracy")
          .withFloor("precision", "precision");

  @Test
  void missingThresholdReturnsDefault() {
    Preferences prefs = new MapPreferences(Map.of());
    TrustRoutingPolicy result =
        io.casehub.api.spi.routing.TrustRoutingPolicyResolver.resolve(prefs, KEYS);
    assertThat(result).isEqualTo(TrustRoutingPolicy.DEFAULT);
  }

  @Test
  void allFieldsResolved() {
    Preferences prefs =
        new MapPreferences(
            Map.of(
                "casehubio.test.trust-routing.threshold", "0.75",
                "casehubio.test.trust-routing.minimum-observations", "15",
                "casehubio.test.trust-routing.borderline-margin", "0.08",
                "casehubio.test.trust-routing.blend-factor", "0.70",
                "casehubio.test.trust-routing.floor.accuracy", "0.65",
                "casehubio.test.trust-routing.floor.precision", "0.60"));
    TrustRoutingPolicy result =
        io.casehub.api.spi.routing.TrustRoutingPolicyResolver.resolve(prefs, KEYS);

    assertThat(result.threshold()).isEqualTo(0.75);
    assertThat(result.minimumObservations()).isEqualTo(15);
    assertThat(result.borderlineMargin()).isEqualTo(0.08);
    assertThat(result.blendFactor()).isEqualTo(0.70);
    assertThat(result.qualityFloors()).containsEntry("accuracy", 0.65);
    assertThat(result.qualityFloors()).containsEntry("precision", 0.60);
  }

  @Test
  void missingFieldsFallBackToDefault() {
    Preferences prefs =
        new MapPreferences(Map.of("casehubio.test.trust-routing.threshold", "0.80"));
    TrustRoutingPolicy result =
        io.casehub.api.spi.routing.TrustRoutingPolicyResolver.resolve(prefs, KEYS);

    assertThat(result.threshold()).isEqualTo(0.80);
    assertThat(result.minimumObservations())
        .isEqualTo(TrustRoutingPolicy.DEFAULT.minimumObservations());
    assertThat(result.borderlineMargin()).isEqualTo(TrustRoutingPolicy.DEFAULT.borderlineMargin());
    assertThat(result.blendFactor()).isEqualTo(TrustRoutingPolicy.DEFAULT.blendFactor());
    assertThat(result.qualityFloors()).isEmpty();
  }

  @Test
  void zeroFloorValuesExcluded() {
    Preferences prefs =
        new MapPreferences(
            Map.of(
                "casehubio.test.trust-routing.threshold", "0.70",
                "casehubio.test.trust-routing.floor.accuracy", "0.0",
                "casehubio.test.trust-routing.floor.precision", "0.50"));
    TrustRoutingPolicy result =
        io.casehub.api.spi.routing.TrustRoutingPolicyResolver.resolve(prefs, KEYS);

    assertThat(result.qualityFloors()).doesNotContainKey("accuracy");
    assertThat(result.qualityFloors()).containsEntry("precision", 0.50);
  }

  @Test
  void bootstrapEscalationPassedThrough() {
    Preferences prefs =
        new MapPreferences(Map.of("casehubio.test.trust-routing.threshold", "0.70"));
    TrustRoutingPolicy result =
        io.casehub.api.spi.routing.TrustRoutingPolicyResolver.resolve(prefs, KEYS, true);
    assertThat(result.bootstrapEscalationRequired()).isTrue();
  }

  @Test
  void keysWithNoFloors() {
    io.casehub.api.spi.routing.TrustRoutingPolicyKeys noFloors =
        io.casehub.api.spi.routing.TrustRoutingPolicyKeys.create("casehubio.simple");
    Preferences prefs = new MapPreferences(Map.of("casehubio.simple.threshold", "0.65"));
    TrustRoutingPolicy result =
        io.casehub.api.spi.routing.TrustRoutingPolicyResolver.resolve(prefs, noFloors);

    assertThat(result.threshold()).isEqualTo(0.65);
    assertThat(result.qualityFloors()).isEmpty();
  }

  @Test
  void collectFloorsUtility() {
    Preferences prefs =
        new MapPreferences(
            Map.of(
                "casehubio.test.trust-routing.floor.accuracy", "0.70",
                "casehubio.test.trust-routing.floor.precision", "0.0"));
    Map<String, Double> floors =
        io.casehub.api.spi.routing.TrustRoutingPolicyResolver.collectFloors(
            prefs, KEYS.allFloorKeys());

    assertThat(floors).containsEntry("accuracy", 0.70);
    assertThat(floors).doesNotContainKey("precision");
  }

  @Test
  void cbrWeightResolved() {
    Preferences prefs =
        new MapPreferences(
            Map.of(
                "casehubio.test.trust-routing.threshold", "0.70",
                "casehubio.test.trust-routing.cbr-weight", "0.2"));
    TrustRoutingPolicy result =
        io.casehub.api.spi.routing.TrustRoutingPolicyResolver.resolve(prefs, KEYS);
    assertThat(result.cbrWeight()).isEqualTo(0.2);
  }

  @Test
  void cbrWeightDefaultsToZero() {
    Preferences prefs =
        new MapPreferences(Map.of("casehubio.test.trust-routing.threshold", "0.70"));
    TrustRoutingPolicy result =
        io.casehub.api.spi.routing.TrustRoutingPolicyResolver.resolve(prefs, KEYS);
    assertThat(result.cbrWeight()).isEqualTo(0.0);
  }
}
