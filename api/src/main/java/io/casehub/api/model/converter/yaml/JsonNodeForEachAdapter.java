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
package io.casehub.api.model.converter.yaml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.yaml.core.foreach.ForEachAdapter;
import io.casehub.yaml.core.foreach.ForEachDirective;
import io.casehub.yaml.core.resolver.VariableResolver;
import java.util.Map;

public final class JsonNodeForEachAdapter implements ForEachAdapter<JsonNode> {

  private final ObjectMapper mapper;
  private final String forEachField;
  private final String whenField;

  public JsonNodeForEachAdapter(ObjectMapper mapper, String forEachField, String whenField) {
    this.mapper = mapper;
    this.forEachField = forEachField;
    this.whenField = whenField;
  }

  @Override
  public JsonNode stamp(JsonNode template, String stampedId, VariableResolver scopedResolver) {
    @SuppressWarnings("unchecked")
    Map<String, Object> map = mapper.convertValue(template, Map.class);
    Map<String, Object> resolved = scopedResolver.resolveMap(map, stampedId);
    ObjectNode result = mapper.valueToTree(resolved);
    result.remove(forEachField);
    return result;
  }

  @Override
  public ForEachDirective getForEach(JsonNode element) {
    JsonNode node = element.get(forEachField);
    if (node == null || node.isNull()) {
      return null;
    }
    return new ForEachDirective.GroupRef(node.asText());
  }

  @Override
  public String getId(JsonNode element) {
    JsonNode name = element.get("name");
    return name != null ? name.asText() : null;
  }

  @Override
  public String getWhen(JsonNode element) {
    if (whenField == null) return null;
    JsonNode node = element.get(whenField);
    if (node == null || node.isNull()) return null;
    return node.asText();
  }
}
