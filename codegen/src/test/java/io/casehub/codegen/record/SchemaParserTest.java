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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaParserTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  @Test
  void parsesDefTypes() throws Exception {
    var schema =
        YAML.readTree(
            """
        $defs:
          Foo:
            type: object
            properties:
              name:
                type: string
              count:
                type: integer
              tags:
                type: array
                items:
                  type: string
              metadata:
                type: object
                additionalProperties:
                  type: string
        type: object
        properties:
          title:
            type: string
        """);

    Map<String, SchemaType> types = SchemaParser.parse(schema);

    assertTrue(types.containsKey("Foo"));
    SchemaType foo = types.get("Foo");
    assertEquals(4, foo.fields().size());

    SchemaField name = foo.fieldByName("name");
    assertEquals("string", name.schemaType());
    assertFalse(name.isArray());
    assertFalse(name.isMap());

    SchemaField count = foo.fieldByName("count");
    assertEquals("integer", count.schemaType());

    SchemaField tags = foo.fieldByName("tags");
    assertTrue(tags.isArray());
    assertEquals("string", tags.schemaType());

    SchemaField metadata = foo.fieldByName("metadata");
    assertTrue(metadata.isMap());
    assertEquals("string", metadata.mapValueType());

    assertTrue(types.containsKey("CaseDefinition"));
    SchemaType root = types.get("CaseDefinition");
    assertEquals(1, root.fields().size());
  }

  @Test
  void parsesRefFields() throws Exception {
    var schema =
        YAML.readTree(
            """
        $defs:
          Bar:
            type: object
            properties:
              baz:
                $ref: "#/$defs/Baz"
              items:
                type: array
                items:
                  $ref: "#/$defs/Baz"
          Baz:
            type: object
            properties:
              value:
                type: string
        type: object
        properties: {}
        """);

    Map<String, SchemaType> types = SchemaParser.parse(schema);
    SchemaType bar = types.get("Bar");

    SchemaField baz = bar.fieldByName("baz");
    assertEquals("ref", baz.schemaType());
    assertEquals("Baz", baz.refTarget());

    SchemaField items = bar.fieldByName("items");
    assertTrue(items.isArray());
    assertEquals("ref", items.schemaType());
    assertEquals("Baz", items.refTarget());
  }

  @Test
  void parsesMapWithTypedValues() throws Exception {
    var schema =
        YAML.readTree(
            """
        $defs:
          Config:
            type: object
            properties:
              weights:
                type: object
                additionalProperties:
                  type: number
              boolMap:
                type: object
                additionalProperties:
                  type: boolean
        type: object
        properties: {}
        """);

    Map<String, SchemaType> types = SchemaParser.parse(schema);
    SchemaType config = types.get("Config");

    SchemaField weights = config.fieldByName("weights");
    assertTrue(weights.isMap());
    assertEquals("number", weights.mapValueType());

    SchemaField boolMap = config.fieldByName("boolMap");
    assertTrue(boolMap.isMap());
    assertEquals("boolean", boolMap.mapValueType());
  }

  @Test
  void parsesRealSchemaRootType() throws Exception {
    var schema =
        YAML.readTree(
            """
        $defs:
          Spec:
            type: object
            properties:
              monitoring:
                type: object
                properties:
                  enabled:
                    type: boolean
        type: object
        properties:
          name:
            type: string
          spec:
            $ref: "#/$defs/Spec"
          labels:
            type: array
            items:
              type: string
        """);

    Map<String, SchemaType> types = SchemaParser.parse(schema);

    SchemaType root = types.get("CaseDefinition");
    assertNotNull(root);
    assertEquals(3, root.fields().size());
    assertNotNull(root.fieldByName("name"));
    assertNotNull(root.fieldByName("spec"));
    assertNotNull(root.fieldByName("labels"));

    SchemaField spec = root.fieldByName("spec");
    assertEquals("ref", spec.schemaType());
    assertEquals("Spec", spec.refTarget());
  }
}
