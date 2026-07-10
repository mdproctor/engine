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

import com.fasterxml.jackson.databind.JsonNode;

public class JsonNodeBridge implements ContextBridge<JsonNode> {

  @Override
  public JsonNode initialise(CaseContext context, JsonNode narrowedInput) {
    return narrowedInput;
  }

  @Override
  public JsonNode serialise(JsonNode context) {
    return context;
  }

  @Override
  public JsonNode deserialise(JsonNode payload) {
    return payload;
  }

  @Override
  public Class<JsonNode> contextType() {
    return JsonNode.class;
  }
}
