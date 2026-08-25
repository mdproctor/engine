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
package io.casehub.generator.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.CaseDefinition;
import io.casehub.generator.CaseHubSchemaGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EnumInliningModuleTest {

  private static JsonNode schema;

  @BeforeAll
  static void generateSchema() {
    schema = new CaseHubSchemaGenerator().generate(CaseDefinition.class);
  }

  @Test
  void outcomePolicy_enumFields_areInlinedNotRefs() {
    JsonNode onDecline = schema.at("/$defs/OutcomePolicy/properties/onDecline");
    assertFalse(onDecline.has("$ref"), "onDecline should not use $ref");
    assertEquals("string", onDecline.path("type").asText());
    assertTrue(onDecline.has("enum"), "onDecline should have enum values");

    JsonNode onFailure = schema.at("/$defs/OutcomePolicy/properties/onFailure");
    assertFalse(onFailure.has("$ref"), "onFailure should not use $ref");
    assertEquals("string", onFailure.path("type").asText());
    assertTrue(onFailure.has("enum"), "onFailure should have enum values");
  }

  @Test
  void binding_enumFields_areInlinedNotRefs() {
    JsonNode participation = schema.at("/$defs/Binding/properties/participation");
    assertFalse(participation.has("$ref"), "participation should not use $ref");
    assertEquals("string", participation.path("type").asText());
    assertTrue(participation.has("enum"), "participation should have enum values");

    JsonNode executionMode = schema.at("/$defs/Binding/properties/executionMode");
    assertFalse(executionMode.has("$ref"), "executionMode should not use $ref");
    assertEquals("string", executionMode.path("type").asText());
    assertTrue(executionMode.has("enum"), "executionMode should have enum values");

    JsonNode lifecycleScope = schema.at("/$defs/Binding/properties/lifecycleScope");
    assertFalse(lifecycleScope.has("$ref"), "lifecycleScope should not use $ref");
    assertEquals("string", lifecycleScope.path("type").asText());
    assertTrue(lifecycleScope.has("enum"), "lifecycleScope should have enum values");
  }

  @Test
  void enumTypes_notInDefs() {
    JsonNode defs = schema.path("$defs");
    assertFalse(defs.has("Participation"), "Participation enum should not be in $defs");
    assertFalse(defs.has("LifecycleScope"), "LifecycleScope enum should not be in $defs");
    assertFalse(defs.has("ExecutionMode"), "ExecutionMode enum should not be in $defs");
    assertFalse(defs.has("OutcomeAction"), "OutcomeAction enum should not be in $defs");
    assertFalse(defs.has("SlaStartFrom"), "SlaStartFrom enum should not be in $defs");
    assertFalse(
        defs.has("SideEffectClassification"),
        "SideEffectClassification enum should not be in $defs");
    assertFalse(defs.has("ReplanHint"), "ReplanHint enum should not be in $defs");
  }
}
