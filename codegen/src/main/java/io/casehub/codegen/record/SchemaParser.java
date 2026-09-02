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

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SchemaParser {

  private SchemaParser() {}

  public static Map<String, SchemaType> parse(JsonNode schemaRoot) {
    Map<String, SchemaType> types = new LinkedHashMap<>();

    JsonNode defs = schemaRoot.path("$defs");
    if (!defs.isMissingNode()) {
      Iterator<Map.Entry<String, JsonNode>> it = defs.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> entry = it.next();
        String typeName = entry.getKey();
        JsonNode typeNode = entry.getValue();
        if (isObjectType(typeNode)) {
          types.put(typeName, parseType(typeName, typeNode));
        }
      }
    }

    if (isObjectType(schemaRoot)) {
      types.put("CaseDefinition", parseType("CaseDefinition", schemaRoot));
    }

    return types;
  }

  private static SchemaType parseType(String name, JsonNode typeNode) {
    List<SchemaField> fields = new ArrayList<>();
    JsonNode props = typeNode.path("properties");
    if (!props.isMissingNode()) {
      Iterator<Map.Entry<String, JsonNode>> it = props.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> entry = it.next();
        fields.add(parseField(entry.getKey(), entry.getValue()));
      }
    }
    return new SchemaType(name, fields);
  }

  private static SchemaField parseField(String name, JsonNode fieldNode) {
    if (fieldNode.has("$ref")) {
      String ref = fieldNode.get("$ref").asText();
      String target = ref.substring(ref.lastIndexOf('/') + 1);
      return new SchemaField(name, "ref", false, false, target, null);
    }

    String type = fieldNode.path("type").asText("");

    if ("array".equals(type)) {
      JsonNode items = fieldNode.path("items");
      if (items.has("$ref")) {
        String ref = items.get("$ref").asText();
        String target = ref.substring(ref.lastIndexOf('/') + 1);
        return new SchemaField(name, "ref", true, false, target, null);
      }
      String itemType = items.path("type").asText("object");
      return new SchemaField(name, itemType, true, false, null, null);
    }

    if ("object".equals(type) && fieldNode.has("additionalProperties")) {
      JsonNode addProps = fieldNode.get("additionalProperties");
      if (addProps.isObject()) {
        String valueType = addProps.path("type").asText("object");
        return new SchemaField(name, "object", false, true, null, valueType);
      }
      return new SchemaField(name, "object", false, true, null, "object");
    }

    return new SchemaField(name, type.isEmpty() ? "object" : type);
  }

  private static boolean isObjectType(JsonNode node) {
    return "object".equals(node.path("type").asText(""));
  }
}
