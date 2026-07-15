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
package io.casehub.api.model.cbr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CbrConfigBuilderTest {

  @Test
  void jq_mode_builds_successfully() {
    var config =
        CbrConfig.builder()
            .feature("f1", ".x")
            .feature("f2", ".y")
            .topK(3)
            .minSimilarity(0.5)
            .weight("f1", 2.0)
            .domain("test")
            .caseType("game")
            .vectorWeight(0.7)
            .build();
    assertInstanceOf(JqFeatureExtractor.class, config.featureExtractor());
    assertEquals(3, config.topK());
    assertEquals(0.5, config.minSimilarity());
    assertEquals(Map.of("f1", 2.0), config.weights());
    assertEquals("test", config.domain());
    assertEquals("game", config.caseType());
    assertEquals(0.7, config.vectorWeight());
  }

  @Test
  void lambda_mode_builds_successfully() {
    var config = CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).topK(5).build();
    assertInstanceOf(LambdaFeatureExtractor.class, config.featureExtractor());
  }

  @Test
  void mixing_jq_then_lambda_throws() {
    var builder = CbrConfig.builder().feature("f1", ".x");
    assertThrows(IllegalStateException.class, () -> builder.featureExtractor(ctx -> Map.of()));
  }

  @Test
  void mixing_lambda_then_jq_throws() {
    var builder = CbrConfig.builder().featureExtractor(ctx -> Map.of());
    assertThrows(IllegalStateException.class, () -> builder.feature("f1", ".x"));
  }

  @Test
  void no_features_throws() {
    assertThrows(IllegalStateException.class, () -> CbrConfig.builder().topK(5).build());
  }

  @Test
  void topK_below_1_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CbrConfig.builder().feature("f1", ".x").topK(0).build());
  }

  @Test
  void minSimilarity_out_of_range_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CbrConfig.builder().feature("f1", ".x").minSimilarity(1.1).build());
  }

  @Test
  void vectorWeight_out_of_range_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CbrConfig.builder().feature("f1", ".x").vectorWeight(-0.1).build());
  }

  @Test
  void negative_weight_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CbrConfig.builder().feature("f1", ".x").weight("f1", -1.0).build());
  }

  @Test
  void blank_domain_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CbrConfig.builder().feature("f1", ".x").domain("  ").build());
  }

  @Test
  void null_domain_allowed() {
    var config = CbrConfig.builder().feature("f1", ".x").build();
    assertNull(config.domain());
  }

  @Test
  void defaults_applied() {
    var config = CbrConfig.builder().feature("f1", ".x").build();
    assertEquals(5, config.topK());
    assertEquals(0.0, config.minSimilarity());
    assertEquals(0.5, config.vectorWeight());
    assertTrue(config.weights().isEmpty());
    assertNull(config.caseType());
  }

  @Test
  void cbrType_defaults_to_null() {
    var config = CbrConfig.builder().feature("f1", ".x").build();
    assertNull(config.cbrType());
  }

  @Test
  void cbrType_set_via_builder() {
    var config = CbrConfig.builder().feature("f1", ".x").cbrType("feature-vector").build();
    assertEquals("feature-vector", config.cbrType());
  }

  @Test
  void cbrType_blank_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CbrConfig.builder().feature("f1", ".x").cbrType("  ").build());
  }

  @Test
  void temporalDecayHalfLifeDays_defaults_to_null() {
    var config = CbrConfig.builder().feature("f1", ".x").build();
    assertNull(config.temporalDecayHalfLifeDays());
  }

  @Test
  void temporalDecayHalfLifeDays_set_via_builder() {
    var config = CbrConfig.builder().feature("f1", ".x").temporalDecayHalfLifeDays(30).build();
    assertEquals(30, config.temporalDecayHalfLifeDays());
  }

  @Test
  void temporalDecayHalfLifeDays_zero_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CbrConfig.builder().feature("f1", ".x").temporalDecayHalfLifeDays(0).build());
  }

  @Test
  void temporalDecayHalfLifeDays_negative_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CbrConfig.builder().feature("f1", ".x").temporalDecayHalfLifeDays(-5).build());
  }
}
