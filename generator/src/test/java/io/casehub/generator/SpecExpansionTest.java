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
package io.casehub.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.CaseDefinition;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SpecExpansionTest {

  private static JsonNode schema;

  @BeforeAll
  static void generateSchema() {
    schema = new CaseHubSchemaGenerator().generate(CaseDefinition.class);
  }

  @Test
  void specHasAllExpansionFields() {
    JsonNode specProps = schema.path("$defs").path("CaseDefinitionSpec").path("properties");
    for (String field :
        List.of(
            "reflection",
            "monitoring",
            "adaptation",
            "planningConstraints",
            "recoveryPolicy",
            "portfolioConfig",
            "memoryRetrieval",
            "maxAdaptations",
            "goapActions",
            "workerServiceAccountIds",
            "defaultQuorum",
            "humanTaskContextConstraints",
            "humanTaskWorkloadConstraint")) {
      assertFalse(
          specProps.path(field).isMissingNode(),
          "CaseDefinitionSpec should have '" + field + "' property");
    }
  }

  @Test
  void reflection_hasCorrectStructure() {
    JsonNode reflection =
        schema.path("$defs").path("CaseDefinitionSpec").path("properties").path("reflection");
    assertFalse(reflection.isMissingNode());
    assertEquals("object", reflection.path("type").asText());
    JsonNode props = reflection.path("properties");
    assertTrue(props.has("enabled"));
    assertTrue(props.has("importanceThreshold"));
    assertTrue(props.has("maxUnreflectedOutcomes"));
    assertTrue(props.has("maxSourceMemories"));
    assertTrue(props.has("importanceWeights"));
  }

  @Test
  void monitoring_hasCorrectStructure() {
    JsonNode monitoring =
        schema.path("$defs").path("CaseDefinitionSpec").path("properties").path("monitoring");
    assertFalse(monitoring.isMissingNode());
    assertEquals("object", monitoring.path("type").asText());
    JsonNode props = monitoring.path("properties");
    assertTrue(props.has("enabled"));
    assertTrue(props.has("perCompletionThreshold"));
    assertTrue(props.has("windowSize"));
  }

  @Test
  void adaptation_hasStringOrObjectOneOf() {
    JsonNode adaptation =
        schema.path("$defs").path("CaseDefinitionSpec").path("properties").path("adaptation");
    assertFalse(adaptation.isMissingNode());
    assertTrue(adaptation.has("oneOf"), "adaptation should have oneOf (string preset or object)");
  }

  @Test
  void planningConstraints_hasCorrectStructure() {
    JsonNode pc =
        schema
            .path("$defs")
            .path("CaseDefinitionSpec")
            .path("properties")
            .path("planningConstraints");
    assertFalse(pc.isMissingNode());
    assertEquals("object", pc.path("type").asText());
    JsonNode props = pc.path("properties");
    assertTrue(props.has("timeBudget"));
    assertTrue(props.has("resourceLimit"));
  }

  @Test
  void recoveryPolicy_hasCorrectStructure() {
    JsonNode rp =
        schema.path("$defs").path("CaseDefinitionSpec").path("properties").path("recoveryPolicy");
    assertFalse(rp.isMissingNode());
    assertEquals("object", rp.path("type").asText());
    JsonNode props = rp.path("properties");
    assertTrue(props.has("maxRetries"));
    assertTrue(props.has("enabled"));
  }

  @Test
  void maxAdaptations_isInteger() {
    JsonNode ma =
        schema.path("$defs").path("CaseDefinitionSpec").path("properties").path("maxAdaptations");
    assertFalse(ma.isMissingNode());
    assertEquals("integer", ma.path("type").asText());
  }

  @Test
  void defaultQuorum_hasCorrectStructure() {
    JsonNode dq =
        schema.path("$defs").path("CaseDefinitionSpec").path("properties").path("defaultQuorum");
    assertFalse(dq.isMissingNode());
    assertEquals("object", dq.path("type").asText());
    JsonNode props = dq.path("properties");
    assertTrue(props.has("instances"));
    assertTrue(props.has("required"));
  }

  @Test
  void workerServiceAccountIds_isStringMap() {
    JsonNode wsai =
        schema
            .path("$defs")
            .path("CaseDefinitionSpec")
            .path("properties")
            .path("workerServiceAccountIds");
    assertFalse(wsai.isMissingNode());
    assertEquals("object", wsai.path("type").asText());
    assertEquals("string", wsai.path("additionalProperties").path("type").asText());
  }

  @Test
  void goapActions_isArrayOfObjects() {
    JsonNode ga =
        schema.path("$defs").path("CaseDefinitionSpec").path("properties").path("goapActions");
    assertFalse(ga.isMissingNode());
    assertEquals("array", ga.path("type").asText());
    JsonNode itemProps = ga.path("items").path("properties");
    assertTrue(itemProps.has("name"));
    assertTrue(itemProps.has("preconditions"));
    assertTrue(itemProps.has("effects"));
    assertTrue(itemProps.has("cost"));
  }

  @Test
  void humanTaskWorkloadConstraint_hasCorrectStructure() {
    JsonNode wc =
        schema
            .path("$defs")
            .path("CaseDefinitionSpec")
            .path("properties")
            .path("humanTaskWorkloadConstraint");
    assertFalse(wc.isMissingNode());
    assertEquals("object", wc.path("type").asText());
    JsonNode props = wc.path("properties");
    assertTrue(props.has("maxActiveTaskCount"));
  }
}
