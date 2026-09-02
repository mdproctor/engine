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

public final class MappingParser {

  private MappingParser() {}

  public static RecordMapping parse(JsonNode root) {
    String pkg = root.path("package").asText("");
    List<String> skipPatterns = readStringList(root.path("skipPatterns"));
    Map<String, String> imports = readStringMap(root.path("imports"));
    Map<String, String> deserializers = readStringMap(root.path("deserializers"));
    Map<String, TypeMapping> types = new LinkedHashMap<>();

    JsonNode typesNode = root.path("types");
    if (!typesNode.isMissingNode()) {
      Iterator<Map.Entry<String, JsonNode>> it = typesNode.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> entry = it.next();
        types.put(entry.getKey(), parseTypeMapping(entry.getValue()));
      }
    }

    return new RecordMapping(pkg, skipPatterns, imports, deserializers, types);
  }

  private static TypeMapping parseTypeMapping(JsonNode node) {
    String recordName = node.path("record").asText(null);
    String source = node.path("source").asText(null);
    Map<String, FieldOverride> fields = new LinkedHashMap<>();
    List<ExtraField> extra = new ArrayList<>();

    JsonNode fieldsNode = node.path("fields");
    if (!fieldsNode.isMissingNode()) {
      Iterator<Map.Entry<String, JsonNode>> it = fieldsNode.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> entry = it.next();
        fields.put(entry.getKey(), parseFieldOverride(entry.getValue()));
      }
    }

    JsonNode extraNode = node.path("extra");
    if (extraNode.isArray()) {
      for (JsonNode item : extraNode) {
        extra.add(parseExtraField(item));
      }
    }

    String body = node.path("body").asText(null);
    return new TypeMapping(recordName, source, fields, extra, body);
  }

  private static ExtraField parseExtraField(JsonNode item) {
    return new ExtraField(
        item.path("name").asText(), item.path("type").asText(), item.path("default").asText(null));
  }

  private static FieldOverride parseFieldOverride(JsonNode node) {
    String alias = node.path("alias").asText(null);
    List<String> aliases = null;
    JsonNode aliasNode = node.path("alias");
    if (aliasNode.isArray()) {
      aliases = new ArrayList<>();
      for (JsonNode a : aliasNode) {
        aliases.add(a.asText());
      }
      alias = null;
    }
    return new FieldOverride(
        node.path("name").asText(null),
        node.path("type").asText(null),
        node.path("deserializer").asText(null),
        alias,
        node.path("property").asText(null),
        node.path("default").asText(null),
        aliases);
  }

  private static List<String> readStringList(JsonNode node) {
    List<String> list = new ArrayList<>();
    if (node.isArray()) {
      for (JsonNode item : node) {
        list.add(item.asText());
      }
    }
    return list;
  }

  private static Map<String, String> readStringMap(JsonNode node) {
    Map<String, String> map = new LinkedHashMap<>();
    if (node.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> it = node.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> entry = it.next();
        map.put(entry.getKey(), entry.getValue().asText());
      }
    }
    return map;
  }
}
