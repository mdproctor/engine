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

class CoreModulesTest {

  private static JsonNode schema;

  @BeforeAll
  static void generateSchema() {
    schema = new CaseHubSchemaGenerator().generate(CaseDefinition.class);
  }

  @Test
  void specNesting_capabilitiesUnderSpec() {
    assertFalse(
        schema.get("properties").has("capabilities"), "capabilities should not be at root level");

    JsonNode specProps = specProperties();
    assertTrue(specProps.has("capabilities"), "capabilities should be under spec");
    assertTrue(specProps.has("workers"), "workers should be under spec");
    assertTrue(specProps.has("bindings"), "bindings should be under spec");
    assertTrue(specProps.has("goals"), "goals should be under spec");
    assertTrue(specProps.has("milestones"), "milestones should be under spec");
  }

  @Test
  void specNesting_identityAtRoot() {
    JsonNode rootProps = schema.get("properties");
    assertTrue(rootProps.has("namespace"), "namespace should be at root");
    assertTrue(rootProps.has("name"), "name should be at root");
    assertTrue(rootProps.has("version"), "version should be at root");
    assertTrue(rootProps.has("dsl"), "dsl should be at root");
  }

  @Test
  void specNesting_strategyFieldsUnderSpec() {
    JsonNode specProps = specProperties();
    assertTrue(specProps.has("planningStrategy"), "planningStrategy should be under spec");
    assertTrue(specProps.has("agentRouting"), "agentRouting should be under spec");
    assertTrue(
        specProps.has("decompositionStrategy"), "decompositionStrategy should be under spec");
  }

  @Test
  void unevaluatedProperties_presentOnRootSchema() {
    assertTrue(
        schema.has("unevaluatedProperties"), "Root schema should have unevaluatedProperties");
    assertFalse(
        schema.get("unevaluatedProperties").asBoolean(),
        "Root unevaluatedProperties should be false");
  }

  @Test
  void worker_hasAdditionalPropertiesTrue() {
    JsonNode workerDef = findDef("Worker");
    assertNotNull(workerDef, "Worker should be in $defs");
    assertTrue(
        workerDef.path("additionalProperties").asBoolean(),
        "Worker should have additionalProperties: true");
  }

  @Test
  void worker_hasCapabilitiesNotCapabilityNames() {
    JsonNode workerDef = findDef("Worker");
    assertNotNull(workerDef, "Worker should be in $defs");
    JsonNode workerProps = workerDef.path("properties");
    assertTrue(workerProps.has("capabilities"), "Worker should have 'capabilities' property");
    assertFalse(workerProps.has("capabilityNames"), "Worker should NOT have 'capabilityNames'");
    assertFalse(workerProps.has("function"), "Worker should NOT have 'function'");
  }

  @Test
  void worker_hasSequenceAndContextType() {
    JsonNode workerDef = findDef("Worker");
    JsonNode workerProps = workerDef.path("properties");
    assertTrue(workerProps.has("sequence"), "Worker should have 'sequence'");
    assertTrue(workerProps.has("contextType"), "Worker should have 'contextType'");
    assertTrue(workerProps.has("outputType"), "Worker should have 'outputType'");
  }

  @Test
  void worker_requiredFields() {
    JsonNode workerDef = findDef("Worker");
    JsonNode required = workerDef.get("required");
    assertNotNull(required, "Worker should have required fields");
    boolean hasName = false;
    boolean hasCapabilities = false;
    for (JsonNode r : required) {
      if ("name".equals(r.asText())) hasName = true;
      if ("capabilities".equals(r.asText())) hasCapabilities = true;
    }
    assertTrue(hasName, "Worker should require 'name'");
    assertTrue(hasCapabilities, "Worker should require 'capabilities'");
  }

  @Test
  void caseCompletion_hasTypedAdditionalProperties() {
    JsonNode completionDef = findDef("CaseCompletion");
    assertNotNull(completionDef, "CaseCompletion should be in $defs");

    JsonNode additionalProps = completionDef.path("additionalProperties");
    assertTrue(
        additionalProps.has("$ref"),
        "CaseCompletion additionalProperties should be a $ref to GoalExpression");
  }

  @Test
  void caseCompletion_hasDoneWhenProperty() {
    JsonNode completionDef = findDef("CaseCompletion");
    JsonNode props = completionDef.path("properties");
    assertTrue(props.has("doneWhen"), "CaseCompletion should have doneWhen property");
  }

  private JsonNode specProperties() {
    JsonNode specRef = schema.path("properties").path("spec");
    if (specRef.has("$ref")) {
      return schema.at("/$defs/CaseDefinitionSpec/properties");
    }
    return specRef.path("properties");
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
