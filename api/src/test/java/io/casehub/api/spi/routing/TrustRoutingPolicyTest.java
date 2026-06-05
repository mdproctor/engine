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

import java.util.Map;
import org.junit.jupiter.api.Test;

class TrustRoutingPolicyTest {

  // threshold=0.7, minimumObservations=5, borderlineMargin=0.1
  private static final io.casehub.api.spi.routing.TrustRoutingPolicy POLICY =
      new io.casehub.api.spi.routing.TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of(), false);

  // ---- isBootstrap --------------------------------------------------------

  @Test
  void isBootstrap_belowMinimumObservations_returnsTrue() {
    assertThat(POLICY.isBootstrap(4)).isTrue();
  }

  @Test
  void isBootstrap_atMinimumObservations_returnsFalse() {
    assertThat(POLICY.isBootstrap(5)).isFalse();
  }

  @Test
  void isBootstrap_aboveMinimumObservations_returnsFalse() {
    assertThat(POLICY.isBootstrap(10)).isFalse();
  }

  @Test
  void isBootstrap_zeroObservations_returnsTrue() {
    assertThat(POLICY.isBootstrap(0)).isTrue();
  }

  // ---- isBorderline -------------------------------------------------------

  @Test
  void isBorderline_scoreExactlyAtThreshold_returnsTrue() {
    // |0.7 - 0.7| = 0.0 ≤ 0.1
    assertThat(POLICY.isBorderline(0.7)).isTrue();
  }

  @Test
  void isBorderline_scoreAtLowerBoundary_returnsTrue() {
    // |0.6 - 0.7| = 0.1 ≤ 0.1
    assertThat(POLICY.isBorderline(0.6)).isTrue();
  }

  @Test
  void isBorderline_scoreNearUpperBoundary_returnsTrue() {
    // |0.79 - 0.7| = 0.09 ≤ 0.1 (avoids IEEE 754 imprecision at the exact 0.8 boundary)
    assertThat(POLICY.isBorderline(0.79)).isTrue();
  }

  @Test
  void isBorderline_scoreJustBelowBoundary_returnsFalse() {
    // |0.59 - 0.7| = 0.11 > 0.1
    assertThat(POLICY.isBorderline(0.59)).isFalse();
  }

  @Test
  void isBorderline_scoreJustAboveBoundary_returnsFalse() {
    // |0.81 - 0.7| = 0.11 > 0.1
    assertThat(POLICY.isBorderline(0.81)).isFalse();
  }

  @Test
  void isBorderline_scoreWellBelowThreshold_returnsFalse() {
    assertThat(POLICY.isBorderline(0.3)).isFalse();
  }

  // ---- passesThresholdCheck -----------------------------------------------

  @Test
  void passesThresholdCheck_scoreAboveThresholdAndNotBorderline_returnsTrue() {
    // |0.85 - 0.7| = 0.15 > 0.1 → not borderline; 0.85 >= 0.7 → passes
    assertThat(POLICY.passesThresholdCheck(0.85)).isTrue();
  }

  @Test
  void passesThresholdCheck_scoreAboveThresholdButBorderline_returnsFalse() {
    // |0.75 - 0.7| = 0.05 ≤ 0.1 → borderline → does not pass
    assertThat(POLICY.passesThresholdCheck(0.75)).isFalse();
  }

  @Test
  void passesThresholdCheck_scoreBelowThreshold_returnsFalse() {
    // 0.5 < 0.7
    assertThat(POLICY.passesThresholdCheck(0.5)).isFalse();
  }

  @Test
  void passesThresholdCheck_scoreAtExactThreshold_returnsFalse() {
    // exactly 0.7 → borderline → does not pass
    assertThat(POLICY.passesThresholdCheck(0.7)).isFalse();
  }
}
