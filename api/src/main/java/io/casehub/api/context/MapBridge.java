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
package io.casehub.api.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

@SuppressWarnings("unchecked")
public class MapBridge implements ContextBridge<Map<String, Object>> {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  @Override
  public Map<String, Object> initialise(CaseContext context, JsonNode narrowedInput) {
    return MAPPER.convertValue(narrowedInput, MAP_TYPE);
  }

  @Override
  public JsonNode serialise(Map<String, Object> context) {
    return MAPPER.valueToTree(context);
  }

  @Override
  public Map<String, Object> deserialise(JsonNode payload) {
    return MAPPER.convertValue(payload, MAP_TYPE);
  }

  @Override
  public Class<Map<String, Object>> contextType() {
    return (Class) Map.class;
  }
}
