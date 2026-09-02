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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class RecordEmitter {

  private static final String LICENSE =
      """
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
       */""";

  private RecordEmitter() {}

  public static String emit(SchemaType schemaType, TypeMapping typeMapping, RecordMapping mapping) {
    String recordName = typeMapping.recordName();
    List<ComponentInfo> components = resolveComponents(schemaType, typeMapping, mapping);
    Set<String> imports = resolveImports(components, mapping);

    StringBuilder sb = new StringBuilder();
    sb.append(LICENSE).append("\n");
    sb.append("package ").append(mapping.packageName()).append(";\n\n");

    for (String imp : imports) {
      sb.append("import ").append(imp).append(";\n");
    }
    if (!imports.isEmpty()) sb.append("\n");

    sb.append("@JsonIgnoreProperties(ignoreUnknown = true)\n");
    sb.append("public record ").append(recordName).append("(\n");

    for (int i = 0; i < components.size(); i++) {
      ComponentInfo c = components.get(i);
      sb.append("    ");
      if (c.annotations() != null && !c.annotations().isEmpty()) {
        sb.append(c.annotations()).append(" ");
      }
      sb.append(c.javaType()).append(" ").append(c.name());
      if (i < components.size() - 1) sb.append(",");
      sb.append("\n");
    }
    sb.append(")");

    List<ComponentInfo> defaultable =
        components.stream().filter(ComponentInfo::needsDefault).toList();

    boolean hasBody = typeMapping.body() != null && !typeMapping.body().isBlank();

    if (defaultable.isEmpty() && !hasBody) {
      sb.append(" {}\n");
    } else {
      sb.append(" {\n\n");
      if (!defaultable.isEmpty()) {
        sb.append("  public ").append(recordName).append(" {\n");
        for (ComponentInfo c : defaultable) {
          sb.append("    if (").append(c.name()).append(" == null) {\n");
          sb.append("      ").append(c.name()).append(" = ").append(c.defaultValue()).append(";\n");
          sb.append("    }\n");
        }
        sb.append("  }\n");
      }
      if (hasBody) {
        sb.append("\n");
        for (String line : typeMapping.body().lines().toList()) {
          sb.append("  ").append(line).append("\n");
        }
      }
      sb.append("}\n");
    }

    return sb.toString();
  }

  private static List<ComponentInfo> resolveComponents(
      SchemaType schemaType, TypeMapping typeMapping, RecordMapping mapping) {
    List<ComponentInfo> components = new ArrayList<>();

    for (SchemaField field : schemaType.fields()) {
      if (shouldSkip(field.name(), mapping.skipPatterns())) continue;

      FieldOverride override = typeMapping.fields().get(field.name());

      String fieldName = field.name();
      if (override != null && override.name() != null) {
        fieldName = override.name();
      }

      String javaType;
      if (override != null && override.type() != null) {
        javaType = override.type();
      } else {
        javaType = resolveJavaType(field, mapping);
      }

      String annotations = buildAnnotations(field.name(), fieldName, override, mapping);
      String defVal =
          (override != null && override.defaultValue() != null)
              ? override.defaultValue()
              : defaultForType(javaType);
      boolean needsDef = defVal != null;
      components.add(new ComponentInfo(fieldName, javaType, annotations, needsDef, defVal));
    }

    Set<String> componentNames = new java.util.HashSet<>();
    for (ComponentInfo c : components) {
      componentNames.add(c.name());
    }

    for (ExtraField extra : typeMapping.extra()) {
      if (componentNames.contains(extra.name())) continue;
      FieldOverride override = typeMapping.fields().get(extra.name());
      String annotations =
          override != null ? buildAnnotations(extra.name(), extra.name(), override, mapping) : null;
      String defVal =
          extra.defaultValue() != null
              ? extra.defaultValue()
              : (override != null && override.defaultValue() != null)
                  ? override.defaultValue()
                  : defaultForType(extra.type());
      boolean needsDef = defVal != null;
      components.add(new ComponentInfo(extra.name(), extra.type(), annotations, needsDef, defVal));
    }

    return components;
  }

  private static boolean shouldSkip(String fieldName, List<String> skipPatterns) {
    for (String pattern : skipPatterns) {
      if (pattern.endsWith("*")) {
        String prefix = pattern.substring(0, pattern.length() - 1);
        if (fieldName.startsWith(prefix)) return true;
      } else if (pattern.equals(fieldName)) {
        return true;
      }
    }
    return false;
  }

  private static String resolveJavaType(SchemaField field, RecordMapping mapping) {
    if (field.isArray()) {
      if (field.refTarget() != null) {
        String resolved = resolveRefType(field.refTarget(), mapping);
        return "List<" + resolved + ">";
      }
      return "List<" + mapPrimitive(field.schemaType()) + ">";
    }
    if (field.isMap()) {
      return "Map<String, " + mapPrimitive(field.mapValueType()) + ">";
    }
    if ("ref".equals(field.schemaType()) && field.refTarget() != null) {
      return resolveRefType(field.refTarget(), mapping);
    }
    return mapPrimitive(field.schemaType());
  }

  private static String resolveRefType(String refTarget, RecordMapping mapping) {
    TypeMapping targetMapping = mapping.types().get(refTarget);
    if (targetMapping != null && targetMapping.recordName() != null) {
      return targetMapping.recordName();
    }
    return refTarget;
  }

  private static String mapPrimitive(String schemaType) {
    return switch (schemaType) {
      case "string" -> "String";
      case "integer" -> "Integer";
      case "number" -> "Double";
      case "boolean" -> "Boolean";
      default -> "JsonNode";
    };
  }

  private static String buildAnnotations(
      String schemaName, String fieldName, FieldOverride override, RecordMapping mapping) {
    if (override == null) return null;
    List<String> annotations = new ArrayList<>();

    if (override.deserializer() != null) {
      annotations.add("@JsonDeserialize(using = " + override.deserializer() + ".class)");
    }
    if (override.aliases() != null && !override.aliases().isEmpty()) {
      StringBuilder aliasBuilder = new StringBuilder("@JsonAlias({");
      for (int i = 0; i < override.aliases().size(); i++) {
        if (i > 0) aliasBuilder.append(", ");
        aliasBuilder.append("\"").append(override.aliases().get(i)).append("\"");
      }
      aliasBuilder.append("})");
      annotations.add(aliasBuilder.toString());
    } else if (override.alias() != null) {
      annotations.add("@JsonAlias(\"" + override.alias() + "\")");
    }
    if (override.property() != null) {
      annotations.add("@JsonProperty(\"" + override.property() + "\")");
    }

    return annotations.isEmpty() ? null : String.join(" ", annotations);
  }

  private static Set<String> resolveImports(List<ComponentInfo> components, RecordMapping mapping) {
    Set<String> imports = new TreeSet<>();
    imports.add("com.fasterxml.jackson.annotation.JsonIgnoreProperties");

    boolean hasList = false;
    boolean hasMap = false;
    boolean hasSet = false;

    for (ComponentInfo c : components) {
      if (c.javaType().startsWith("List<")) hasList = true;
      if (c.javaType().startsWith("Map<")) hasMap = true;
      if (c.javaType().startsWith("Set<")) hasSet = true;

      collectTypeImports(c.javaType(), mapping, imports);

      if (c.annotations() != null) {
        if (c.annotations().contains("@JsonDeserialize")) {
          imports.add("com.fasterxml.jackson.databind.annotation.JsonDeserialize");
          String deser = extractDeserializer(c.annotations());
          if (deser != null && mapping.deserializers().containsKey(deser)) {
            imports.add(mapping.deserializers().get(deser));
          }
        }
        if (c.annotations().contains("@JsonAlias")) {
          imports.add("com.fasterxml.jackson.annotation.JsonAlias");
        }
        if (c.annotations().contains("@JsonProperty")) {
          imports.add("com.fasterxml.jackson.annotation.JsonProperty");
        }
      }
    }

    if (hasList) imports.add("java.util.List");
    if (hasMap) imports.add("java.util.Map");
    if (hasSet) imports.add("java.util.Set");

    return imports;
  }

  private static void collectTypeImports(
      String javaType, RecordMapping mapping, Set<String> imports) {
    String baseType = extractBaseType(javaType);
    if (mapping.imports().containsKey(baseType)) {
      imports.add(mapping.imports().get(baseType));
    }
    if (javaType.contains("<") && javaType.contains(",")) {
      String inner = javaType.substring(javaType.indexOf(',') + 1, javaType.lastIndexOf('>'));
      String trimmed = inner.trim();
      if (mapping.imports().containsKey(trimmed)) {
        imports.add(mapping.imports().get(trimmed));
      }
    }
  }

  private static String extractBaseType(String javaType) {
    if (javaType.contains("<")) {
      int start = javaType.indexOf('<') + 1;
      int end = javaType.lastIndexOf('>');
      String inner = javaType.substring(start, end);
      if (inner.contains(",")) {
        return inner.substring(inner.lastIndexOf(' ') + 1).trim();
      }
      return inner;
    }
    return javaType;
  }

  private static String extractDeserializer(String annotations) {
    int start = annotations.indexOf("using = ");
    if (start < 0) return null;
    start += 8;
    int end = annotations.indexOf(".class", start);
    if (end < 0) return null;
    return annotations.substring(start, end);
  }

  private static boolean isCollectionType(String javaType) {
    return javaType.startsWith("List<")
        || javaType.startsWith("Map<")
        || javaType.startsWith("Set<");
  }

  private static String defaultForType(String javaType) {
    if (javaType.startsWith("List<")) return "List.of()";
    if (javaType.startsWith("Map<")) return "Map.of()";
    if (javaType.startsWith("Set<")) return "Set.of()";
    return null;
  }

  record ComponentInfo(
      String name,
      String javaType,
      String annotations,
      boolean needsDefault,
      String defaultValue) {}
}
