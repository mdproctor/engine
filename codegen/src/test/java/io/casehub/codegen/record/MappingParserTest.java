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
package io.casehub.codegen.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

class MappingParserTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  @Test
  void parsesTypeMapping() throws Exception {
    var mapping =
        YAML.readTree(
            """
        package: io.casehub.api.model.converter.yaml
        skipPatterns:
          - "_codegen*"
        imports:
          Trigger: io.casehub.api.model.Trigger
        deserializers:
          TriggerDeserializer: io.casehub.api.model.converter.deser.TriggerDeserializer
        types:
          Binding:
            record: YamlBinding
            fields:
              on:
                type: Trigger
                deserializer: TriggerDeserializer
              replanHint:
                alias: replanAfter
              doBlock:
                property: do
                type: JsonNode
            extra:
              - name: judgment
                type: YamlJudgmentTarget
        """);

    RecordMapping result = MappingParser.parse(mapping);

    assertEquals("io.casehub.api.model.converter.yaml", result.packageName());
    assertEquals(1, result.skipPatterns().size());
    assertEquals("_codegen*", result.skipPatterns().get(0));
    assertEquals("io.casehub.api.model.Trigger", result.imports().get("Trigger"));

    TypeMapping binding = result.types().get("Binding");
    assertNotNull(binding);
    assertEquals("YamlBinding", binding.recordName());

    FieldOverride on = binding.fields().get("on");
    assertEquals("Trigger", on.type());
    assertEquals("TriggerDeserializer", on.deserializer());

    FieldOverride replan = binding.fields().get("replanHint");
    assertEquals("replanAfter", replan.alias());

    FieldOverride doBlock = binding.fields().get("doBlock");
    assertEquals("do", doBlock.property());
    assertEquals("JsonNode", doBlock.type());

    assertEquals(1, binding.extra().size());
    assertEquals("judgment", binding.extra().get(0).name());
    assertEquals("YamlJudgmentTarget", binding.extra().get(0).type());
  }

  @Test
  void parsesSimpleTypeWithNoOverrides() throws Exception {
    var mapping =
        YAML.readTree(
            """
        package: test.pkg
        types:
          Simple:
            record: YamlSimple
        """);

    RecordMapping result = MappingParser.parse(mapping);
    TypeMapping simple = result.types().get("Simple");
    assertNotNull(simple);
    assertEquals("YamlSimple", simple.recordName());
    assertTrue(simple.fields().isEmpty());
    assertTrue(simple.extra().isEmpty());
  }

  @Test
  void handlesEmptyOptionalSections() throws Exception {
    var mapping =
        YAML.readTree(
            """
        package: test.pkg
        types:
          Minimal:
            record: YamlMinimal
        """);

    RecordMapping result = MappingParser.parse(mapping);
    assertTrue(result.skipPatterns().isEmpty());
    assertTrue(result.imports().isEmpty());
    assertTrue(result.deserializers().isEmpty());
  }
}
