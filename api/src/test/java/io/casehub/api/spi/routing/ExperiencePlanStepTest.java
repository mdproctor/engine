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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ExperiencePlanStepTest {

  @Test
  void valid_construction() {
    var step = new ExperiencePlanStep("bind1", "cap1", "worker1", "SUCCESS", 1, Map.of("k", "v"));
    assertEquals("bind1", step.bindingName());
    assertEquals(1, step.priority());
  }

  @Test
  void null_bindingName_throws() {
    assertThrows(
        NullPointerException.class,
        () -> new ExperiencePlanStep(null, "cap", "w", "ok", 0, Map.of()));
  }

  @Test
  void null_capabilityName_accepted() {
    var step = new ExperiencePlanStep("b", null, "w", "ok", 0, Map.of());
    assertNull(step.capabilityName());
  }

  @Test
  void negative_priority_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExperiencePlanStep("b", "c", "w", "ok", -1, Map.of()));
  }

  @Test
  void null_parameters_defaults_to_empty() {
    var step = new ExperiencePlanStep("b", "c", "w", "ok", 0, null);
    assertTrue(step.parameters().isEmpty());
  }

  @Test
  void adaptation_fields_populated() {
    var step =
        new ExperiencePlanStep(
            "bind1",
            "cap1",
            "worker1",
            "SUCCESS",
            0,
            Map.of(),
            "BOOSTED",
            "high relevance to current case");
    assertEquals("BOOSTED", step.adaptationAction());
    assertEquals("high relevance to current case", step.adaptationReason());
  }

  @Test
  void adaptation_fields_nullable() {
    var step =
        new ExperiencePlanStep("bind1", "cap1", "worker1", "SUCCESS", 0, Map.of(), null, null);
    assertNull(step.adaptationAction());
    assertNull(step.adaptationReason());
  }

  @Test
  void convenience_constructor_nulls_adaptation_fields() {
    var step = new ExperiencePlanStep("bind1", "cap1", "worker1", "SUCCESS", 0, Map.of());
    assertNull(step.adaptationAction());
    assertNull(step.adaptationReason());
  }
}
