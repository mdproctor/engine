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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordEmitterTest {

  private static final RecordMapping MAPPING =
      new RecordMapping(
          "io.casehub.api.model.converter.yaml",
          List.of("_codegen*"),
          Map.of(
              "Trigger", "io.casehub.api.model.Trigger",
              "JsonNode", "com.fasterxml.jackson.databind.JsonNode"),
          Map.of("TriggerDeserializer", "io.casehub.api.model.converter.deser.TriggerDeserializer"),
          Map.of());

  @Test
  void emitsSimpleRecord() {
    var schemaType =
        new SchemaType(
            "Foo", List.of(new SchemaField("name", "string"), new SchemaField("count", "integer")));
    var typeMapping = new TypeMapping("YamlFoo", null, Map.of(), List.of());

    String source = RecordEmitter.emit(schemaType, typeMapping, MAPPING);

    assertTrue(source.contains("package io.casehub.api.model.converter.yaml;"));
    assertTrue(source.contains("@JsonIgnoreProperties(ignoreUnknown = true)"));
    assertTrue(source.contains("public record YamlFoo("));
    assertTrue(source.contains("String name"));
    assertTrue(source.contains("Integer count"));
  }

  @Test
  void emitsListDefaultsInCompactConstructor() {
    var schemaType =
        new SchemaType(
            "Bar",
            List.of(
                new SchemaField("tags", "string", true, false, null, null),
                new SchemaField("meta", "object", false, true, null, "string")));
    var typeMapping = new TypeMapping("YamlBar", null, Map.of(), List.of());

    String source = RecordEmitter.emit(schemaType, typeMapping, MAPPING);

    assertTrue(source.contains("List<String> tags"));
    assertTrue(source.contains("Map<String, String> meta"));
    assertTrue(source.contains("public YamlBar {"));
    assertTrue(source.contains("if (tags == null)"));
    assertTrue(source.contains("tags = List.of()"));
    assertTrue(source.contains("if (meta == null)"));
    assertTrue(source.contains("meta = Map.of()"));
  }

  @Test
  void emitsFieldOverrides() {
    var schemaType =
        new SchemaType(
            "Baz",
            List.of(
                new SchemaField("on", "ref", false, false, "Trigger", null),
                new SchemaField("hint", "string")));
    var typeMapping =
        new TypeMapping(
            "YamlBaz",
            null,
            Map.of(
                "on", new FieldOverride("Trigger", "TriggerDeserializer", null, null),
                "hint", new FieldOverride(null, null, "replanAfter", null)),
            List.of());

    String source = RecordEmitter.emit(schemaType, typeMapping, MAPPING);

    assertTrue(source.contains("@JsonDeserialize(using = TriggerDeserializer.class) Trigger on"));
    assertTrue(source.contains("@JsonAlias(\"replanAfter\") String hint"));
    assertTrue(source.contains("import io.casehub.api.model.Trigger;"));
    assertTrue(source.contains("import io.casehub.api.model.converter.deser.TriggerDeserializer;"));
  }

  @Test
  void emitsJsonPropertyAnnotation() {
    var schemaType = new SchemaType("Qux", List.of());
    var typeMapping =
        new TypeMapping(
            "YamlQux",
            null,
            Map.of("doBlock", new FieldOverride("JsonNode", null, null, "do")),
            List.of(new ExtraField("doBlock", "JsonNode")));

    String source = RecordEmitter.emit(schemaType, typeMapping, MAPPING);

    assertTrue(source.contains("@JsonProperty(\"do\") JsonNode doBlock"));
  }

  @Test
  void emitsExtraFields() {
    var schemaType = new SchemaType("Parent", List.of(new SchemaField("name", "string")));
    var typeMapping =
        new TypeMapping(
            "YamlParent",
            null,
            Map.of(),
            List.of(new ExtraField("child", "YamlChild"), new ExtraField("items", "List<String>")));

    String source = RecordEmitter.emit(schemaType, typeMapping, MAPPING);

    assertTrue(source.contains("String name"));
    assertTrue(source.contains("YamlChild child"));
    assertTrue(source.contains("List<String> items"));
  }

  @Test
  void skipsFieldsMatchingSkipPattern() {
    var schemaType =
        new SchemaType(
            "Spec",
            List.of(
                new SchemaField("realField", "string"),
                new SchemaField("_codegenFoo", "string"),
                new SchemaField("_codegenBar", "ref", false, false, "Baz", null)));
    var typeMapping = new TypeMapping("YamlSpec", null, Map.of(), List.of());

    String source = RecordEmitter.emit(schemaType, typeMapping, MAPPING);

    assertTrue(source.contains("String realField"));
    assertFalse(source.contains("_codegenFoo"));
    assertFalse(source.contains("_codegenBar"));
  }

  @Test
  void emitsLicenseHeader() {
    var schemaType = new SchemaType("Lic", List.of(new SchemaField("x", "string")));
    var typeMapping = new TypeMapping("YamlLic", null, Map.of(), List.of());

    String source = RecordEmitter.emit(schemaType, typeMapping, MAPPING);

    assertTrue(source.startsWith("/*\n * Copyright 2026-Present The Case Hub Authors"));
    assertTrue(source.contains("Apache License, Version 2.0"));
  }

  @Test
  void emitsRecordWithNoFields() {
    var schemaType = new SchemaType("Empty", List.of());
    var typeMapping =
        new TypeMapping(
            "YamlEmpty", null, Map.of(), List.of(new ExtraField("root", "YamlHtnNode")));

    String source = RecordEmitter.emit(schemaType, typeMapping, MAPPING);

    assertTrue(source.contains("public record YamlEmpty("));
    assertTrue(source.contains("YamlHtnNode root"));
  }

  @Test
  void emitsSetDefaults() {
    var schemaType = new SchemaType("SetTest", List.of());
    var typeMapping =
        new TypeMapping(
            "YamlSetTest", null, Map.of(), List.of(new ExtraField("outcomes", "Set<String>")));

    String source = RecordEmitter.emit(schemaType, typeMapping, MAPPING);

    assertTrue(source.contains("Set<String> outcomes"));
    assertTrue(source.contains("outcomes = Set.of()"));
    assertTrue(source.contains("import java.util.Set;"));
  }

  @Test
  void fieldCountMatchesSchemaMinusSkipped() {
    var schemaType =
        new SchemaType(
            "Mixed",
            List.of(
                new SchemaField("a", "string"),
                new SchemaField("_codegenX", "string"),
                new SchemaField("b", "integer")));
    var typeMapping =
        new TypeMapping("YamlMixed", null, Map.of(), List.of(new ExtraField("extra", "String")));

    String source = RecordEmitter.emit(schemaType, typeMapping, MAPPING);

    long componentCount =
        source
            .lines()
            .filter(l -> l.trim().startsWith("String ") || l.trim().startsWith("Integer "))
            .count();
    assertEquals(3, componentCount);
  }
}
