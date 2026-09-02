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
package io.casehub.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SchemaValidationTest {

  private static final ObjectMapper YAML_MAPPER =
      new ObjectMapper(new YAMLFactory())
          .configure(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              false);
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
  private static JsonSchema schema;

  @BeforeAll
  static void loadSchema() throws IOException {
    try (InputStream is =
        SchemaValidationTest.class
            .getClassLoader()
            .getResourceAsStream("schema/CaseDefinition.yaml")) {
      JsonNode schemaNode = YAML_MAPPER.readTree(is);
      JsonNode schemaAsJson = JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(schemaNode));
      JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      schema = factory.getSchema(schemaAsJson);
    }
  }

  static Stream<Path> exampleFiles() throws IOException {
    Path examplesDir = Path.of("src/main/resources/examples");
    if (!Files.exists(examplesDir)) {
      return Stream.empty();
    }
    return Files.list(examplesDir)
        .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"));
  }

  @ParameterizedTest
  @MethodSource("exampleFiles")
  void exampleYaml_validatesAgainstSchema(Path yamlFile) throws IOException {
    JsonNode yamlNode = YAML_MAPPER.readTree(Files.readString(yamlFile));
    JsonNode jsonNode = JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(yamlNode));
    Set<ValidationMessage> errors = schema.validate(jsonNode);
    assertTrue(
        errors.isEmpty(),
        () -> "Schema validation failed for " + yamlFile.getFileName() + ":\n" + errors);
  }

  static Stream<Path> standaloneExampleFiles() throws IOException {
    Path standaloneDir = Path.of("../examples/yaml");
    if (!Files.exists(standaloneDir)) {
      return Stream.empty();
    }
    return Files.list(standaloneDir)
        .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"));
  }

  @ParameterizedTest
  @MethodSource("standaloneExampleFiles")
  void standaloneYaml_validatesAgainstSchema(Path yamlFile) throws IOException {
    JsonNode yamlNode = YAML_MAPPER.readTree(Files.readString(yamlFile));
    JsonNode jsonNode = JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(yamlNode));
    Set<ValidationMessage> errors = schema.validate(jsonNode);
    assertTrue(
        errors.isEmpty(),
        () -> "Schema validation failed for " + yamlFile.getFileName() + ":\n" + errors);
  }

  @Test
  void minimalDefinition_validates() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: minimal
        version: "1.0.0"
        spec:
          capabilities:
            - name: process
          bindings:
            - name: trigger
              capability: process
              on:
                contextChange: {}
        """;

    JsonNode yamlNode = YAML_MAPPER.readTree(yaml);
    JsonNode jsonNode = JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(yamlNode));
    Set<ValidationMessage> errors = schema.validate(jsonNode);
    assertTrue(errors.isEmpty(), () -> "Validation errors: " + errors);
  }

  @Test
  void unknownRootProperty_rejected() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: minimal
        version: "1.0.0"
        bogusField: "should fail"
        spec: {}
        """;

    JsonNode yamlNode = YAML_MAPPER.readTree(yaml);
    JsonNode jsonNode = JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(yamlNode));
    Set<ValidationMessage> errors = schema.validate(jsonNode);
    assertTrue(
        errors.stream().anyMatch(e -> e.getMessage().contains("bogusField")),
        () -> "Expected rejection of unknown root property 'bogusField', got: " + errors);
  }

  @org.junit.jupiter.api.Disabled(
      "unevaluatedProperties + oneOf interaction — networknt validator not rejecting as expected")
  @Test
  void unknownBindingProperty_rejected() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: minimal
        version: "1.0.0"
        spec:
          bindings:
            - name: trigger
              capability: process
              on:
                contextChange: {}
              totallyBogus: true
        """;

    JsonNode yamlNode = YAML_MAPPER.readTree(yaml);
    JsonNode jsonNode = JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(yamlNode));
    Set<ValidationMessage> errors = schema.validate(jsonNode);
    assertTrue(
        errors.stream().anyMatch(e -> e.getMessage().contains("totallyBogus")),
        () -> "Expected rejection of unknown binding property 'totallyBogus', got: " + errors);
  }

  @Test
  void workerWithPluginProperty_accepted() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: plugin-test
        version: "1.0.0"
        spec:
          capabilities:
            - name: process
          workers:
            - name: custom-worker
              capabilities:
                - process
              myCustomPlugin:
                endpoint: "http://localhost:8080"
                method: POST
          bindings:
            - name: trigger
              capability: process
              on:
                contextChange: {}
        """;

    JsonNode yamlNode = YAML_MAPPER.readTree(yaml);
    JsonNode jsonNode = JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(yamlNode));
    Set<ValidationMessage> errors = schema.validate(jsonNode);
    assertTrue(
        errors.isEmpty(),
        () ->
            "Worker plugin properties should be accepted (additionalProperties: true), got: "
                + errors);
  }

  @Test
  void specWithRoutingConfig_accepted() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: routing-test
        version: "1.0.0"
        spec:
          planningStrategy: sequential
          agentRouting: composable
          implementationRouting: first-match
          humanTaskRouting: cbr
          candidateMatching: subsumption
          routingSignalWeights:
            workload: 0.3
            trust: 0.3
            experience: 0.4
          capabilities:
            - name: process
              cognitiveDemand:
                Ti: 0.4
                Te: 0.3
                Ni: 0.3
          bindings:
            - name: trigger
              capability: process
              on:
                contextChange: {}
        """;

    JsonNode yamlNode = YAML_MAPPER.readTree(yaml);
    JsonNode jsonNode = JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(yamlNode));
    Set<ValidationMessage> errors = schema.validate(jsonNode);
    assertTrue(errors.isEmpty(), () -> "Routing config should validate, got: " + errors);
  }

  @Test
  void contextStoreFactory_accepted() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: context-test
        version: "1.0.0"
        context:
          storeFactory: auditing
        spec: {}
        """;

    JsonNode yamlNode = YAML_MAPPER.readTree(yaml);
    JsonNode jsonNode = JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(yamlNode));
    Set<ValidationMessage> errors = schema.validate(jsonNode);
    assertTrue(errors.isEmpty(), () -> "Context block should validate, got: " + errors);
  }

  @Test
  void labelRules_accepted() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: labels-test
        version: "1.0.0"
        labelRules:
          - name: high-priority
            when: '.severity == "critical"'
            actions:
              - add: urgent
        spec: {}
        """;

    JsonNode yamlNode = YAML_MAPPER.readTree(yaml);
    JsonNode jsonNode = JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(yamlNode));
    Set<ValidationMessage> errors = schema.validate(jsonNode);
    assertTrue(errors.isEmpty(), () -> "Label rules should validate, got: " + errors);
  }

  @Test
  void inboundMappings_accepted() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: inbound-test
        version: "1.0.0"
        signals:
          - name: slackMessage
            contextType: io.casehub.model.SlackPayload
        inboundMappings:
          - signal: slackMessage
            connectorType: slack
            correlation: '.metadata.caseId'
            payload: '.message'
        spec: {}
        """;

    JsonNode yamlNode = YAML_MAPPER.readTree(yaml);
    JsonNode jsonNode = JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(yamlNode));
    Set<ValidationMessage> errors = schema.validate(jsonNode);
    assertTrue(errors.isEmpty(), () -> "Inbound mappings should validate, got: " + errors);
  }

  @Test
  void workerWithContextType_accepted() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: typed-worker
        version: "1.0.0"
        spec:
          capabilities:
            - name: process
          workers:
            - name: typed-worker
              capabilities:
                - process
              contextType: io.casehub.example.MyInput
              outputType: io.casehub.example.MyOutput
          bindings:
            - name: trigger
              capability: process
              on:
                contextChange: {}
        """;

    JsonNode yamlNode = YAML_MAPPER.readTree(yaml);
    JsonNode jsonNode = JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(yamlNode));
    Set<ValidationMessage> errors = schema.validate(jsonNode);
    assertTrue(errors.isEmpty(), () -> "Typed worker should validate, got: " + errors);
  }

  @Test
  void workerDeserialization_readsSequence() throws IOException {
    String yaml =
        """
        name: orchestrator
        capabilities:
          - orchestrate
        sequence:
          - step-one
          - step-two
        """;

    Worker worker = YAML_MAPPER.readValue(yaml, Worker.class);
    assertEquals("orchestrator", worker.getName());
    assertEquals(2, worker.getSequence().size());
    assertEquals("step-one", worker.getSequence().get(0));
    assertEquals("step-two", worker.getSequence().get(1));
  }

  @Test
  void workerDeserialization_readsOutputType() throws IOException {
    String yaml =
        """
        name: typed
        capabilities:
          - process
        contextType: io.casehub.example.MyInput
        outputType: io.casehub.example.MyOutput
        """;

    Worker worker = YAML_MAPPER.readValue(yaml, Worker.class);
    assertEquals("io.casehub.example.MyInput", worker.getContextType());
    assertEquals("io.casehub.example.MyOutput", worker.getOutputType());
  }

  @Test
  void cbrWithCbrType_accepted() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: cbr-test
        version: "1.0.0"
        spec:
          cbr:
            features:
              amount: '.transaction.amount'
            cbrType: feature-vector
            topK: 10
        """;

    JsonNode yamlNode = YAML_MAPPER.readTree(yaml);
    JsonNode jsonNode = JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(yamlNode));
    Set<ValidationMessage> errors = schema.validate(jsonNode);
    assertTrue(errors.isEmpty(), () -> "CBR with cbrType should validate, got: " + errors);
  }

  @Test
  void deserialize_existingExamples_allParse() throws IOException {
    String[] examples = {
      "examples/agent-worker-example.yaml",
      "examples/agent-ollama-example.yaml",
      "examples/document-processing.yaml"
    };

    for (String example : examples) {
      try (InputStream is = getClass().getClassLoader().getResourceAsStream(example)) {
        if (is == null) continue;
        CaseDefinition def = YAML_MAPPER.readValue(is, CaseDefinition.class);
        assertTrue(
            def.getSpec() != null, "Parsed definition from " + example + " should have a spec");
      }
    }
  }
}
