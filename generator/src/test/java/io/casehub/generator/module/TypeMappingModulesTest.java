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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.CaseDefinition;
import io.casehub.generator.CaseHubSchemaGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TypeMappingModulesTest {

  private static JsonNode schema;

  @BeforeAll
  static void generateSchema() {
    schema = new CaseHubSchemaGenerator().generate(CaseDefinition.class);
  }

  @Test
  void trigger_hasNamedPropertyOneOf() {
    JsonNode triggerDef = findDef("Trigger");
    assertNotNull(triggerDef, "Trigger should be in $defs");
    assertTrue(triggerDef.has("oneOf"), "Trigger should have oneOf");
    JsonNode props = triggerDef.path("properties");
    assertTrue(props.has("contextChange"), "Trigger should have contextChange property");
    assertTrue(props.has("schedule"), "Trigger should have schedule property");
    assertTrue(props.has("scopeActivated"), "Trigger should have scopeActivated property");
  }

  @Test
  void trigger_contextChangeIsRef() {
    JsonNode triggerDef = findDef("Trigger");
    JsonNode contextChange = triggerDef.path("properties").path("contextChange");
    assertTrue(contextChange.has("$ref"), "contextChange should be a $ref to ContextChangeTrigger");
  }

  @Test
  void binding_hasTargetOneOf() {
    JsonNode bindingDef = findDef("Binding");
    assertNotNull(bindingDef, "Binding should be in $defs");
    assertTrue(bindingDef.has("oneOf"), "Binding should have oneOf for target types");
    JsonNode props = bindingDef.path("properties");
    assertTrue(props.has("capability"), "Binding should have capability property");
    assertTrue(props.has("subCase"), "Binding should have subCase property");
    assertTrue(props.has("humanTask"), "Binding should have humanTask property");
    assertTrue(props.has("signal"), "Binding should have signal property");
    assertFalse(props.has("target"), "Binding should NOT have raw 'target' field");
  }

  @Test
  void binding_capabilityIsString() {
    JsonNode bindingDef = findDef("Binding");
    JsonNode capability = bindingDef.path("properties").path("capability");
    assertTrue(
        "string".equals(capability.path("type").asText()), "capability should be type: string");
  }

  @Test
  void binding_hasOnProperty() {
    JsonNode bindingDef = findDef("Binding");
    JsonNode props = bindingDef.path("properties");
    assertTrue(props.has("on"), "Binding should have 'on' property");
  }

  @Test
  void expressionOrOverride_isOneOf() {
    JsonNode exprDef = findDef("ExpressionOrOverride");
    assertNotNull(exprDef, "ExpressionOrOverride should be in $defs");
    assertTrue(
        exprDef.has("oneOf"),
        "ExpressionOrOverride should have oneOf (ExpressionOrOverride pattern)");
  }

  @Test
  void goalCondition_usesExpressionOrOverride() {
    JsonNode goalDef = findDef("Goal");
    assertNotNull(goalDef, "Goal should be in $defs");
    JsonNode conditionProp = goalDef.path("properties").path("condition");
    assertTrue(
        conditionProp.has("$ref") || conditionProp.has("oneOf"),
        "Goal.condition should use ExpressionOrOverride pattern");
  }

  private JsonNode findDef(String name) {
    JsonNode defs = schema.get("$defs");
    if (defs == null) return null;
    if (defs.has(name)) return defs.get(name);
    var it = defs.fieldNames();
    while (it.hasNext()) {
      String key = it.next();
      if (key.endsWith("." + name) || key.endsWith("$" + name)) {
        return defs.get(key);
      }
    }
    return null;
  }
}
