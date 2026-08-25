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
import io.casehub.api.model.CaseDefinition;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class SchemaDriftTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void committedSchema_matchesGeneratorOutput() throws Exception {
    JsonNode committed;
    try (InputStream is =
        getClass().getClassLoader().getResourceAsStream("schema/CaseDefinition.yaml")) {
      if (is == null) {
        System.out.println(
            "SKIP: CaseDefinition.yaml not on classpath (add schema module as test dependency)");
        return;
      }
      committed = JSON.readTree(JSON.writeValueAsString(YAML.readTree(is)));
    }

    JsonNode generated = new CaseHubSchemaGenerator().generate(CaseDefinition.class);

    var comparator = new SchemaComparator(true);
    var result = comparator.compare(committed, generated);

    assertTrue(
        result.isEquivalent(),
        () ->
            "Committed schema has drifted from generator output ("
                + result.differences().size()
                + " differences). Regenerate with: mvn process-classes -pl generator\n"
                + result.report());
  }
}
