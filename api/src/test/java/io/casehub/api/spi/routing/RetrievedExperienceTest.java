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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetrievedExperienceTest {

  @Test
  void valid_construction() {
    var step = new ExperiencePlanStep("b1", "cap1", "w1", "SUCCESS", 0, Map.of());
    var exp =
        new RetrievedExperience(
            "problem", "solution", "COMPLETED", 0.9, 0.85, Map.of("f1", "v1"), List.of(step));
    assertEquals("problem", exp.problem());
    assertEquals(0.85, exp.similarityScore());
    assertEquals(1, exp.planTrace().size());
  }

  @Test
  void null_problem_throws() {
    assertThrows(
        NullPointerException.class,
        () -> new RetrievedExperience(null, "s", "o", 0.9, 0.5, Map.of(), List.of()));
  }

  @Test
  void null_solution_throws() {
    assertThrows(
        NullPointerException.class,
        () -> new RetrievedExperience("p", null, "o", 0.9, 0.5, Map.of(), List.of()));
  }

  @Test
  void score_out_of_range_high_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RetrievedExperience("p", "s", "o", 0.9, 1.1, Map.of(), List.of()));
  }

  @Test
  void score_out_of_range_low_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RetrievedExperience("p", "s", "o", 0.9, -1.1, Map.of(), List.of()));
  }

  @Test
  void negative_one_score_is_valid() {
    var exp = new RetrievedExperience("p", "s", "o", null, -1.0, Map.of(), List.of());
    assertEquals(-1.0, exp.similarityScore());
  }

  @Test
  void defensive_copies() {
    var features = new HashMap<String, Object>();
    features.put("k", "v");
    var exp = new RetrievedExperience("p", "s", "o", null, 0.5, features, List.of());
    features.put("k2", "v2");
    assertEquals(1, exp.features().size());
  }

  @Test
  void null_features_defaults_to_empty() {
    var exp = new RetrievedExperience("p", "s", "o", null, 0.5, null, null);
    assertTrue(exp.features().isEmpty());
    assertTrue(exp.planTrace().isEmpty());
  }
}
