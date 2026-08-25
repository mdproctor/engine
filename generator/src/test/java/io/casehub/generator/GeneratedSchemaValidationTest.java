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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.casehub.api.model.CaseDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class GeneratedSchemaValidationTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final ObjectMapper JSON = new ObjectMapper();
  private static JsonSchema generatedSchema;

  @BeforeAll
  static void generateSchema() {
    JsonNode schema = new CaseHubSchemaGenerator().generate(CaseDefinition.class);
    JsonNode schemaAsJson = JSON.convertValue(schema, JsonNode.class);
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    generatedSchema = factory.getSchema(schemaAsJson);
  }

  static Stream<Path> exampleFiles() throws IOException {
    Path examplesDir = Path.of("../schema/src/main/resources/examples");
    if (!Files.exists(examplesDir)) {
      return Stream.empty();
    }
    return Files.list(examplesDir)
        .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"));
  }

  @ParameterizedTest
  @MethodSource("exampleFiles")
  void existingYamlExamples_validateAgainstGeneratedSchema(Path yamlFile) throws IOException {
    JsonNode yamlNode = YAML.readTree(Files.readString(yamlFile));
    JsonNode jsonNode = JSON.readTree(JSON.writeValueAsString(yamlNode));
    Set<ValidationMessage> errors = generatedSchema.validate(jsonNode);
    assertTrue(
        errors.isEmpty(),
        () -> "Generated schema validation failed for " + yamlFile.getFileName() + ":\n" + errors);
  }
}
