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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JacksonPojoBridge<T> implements ContextBridge<T> {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private final Class<T> targetClass;

  public JacksonPojoBridge(Class<T> targetClass) {
    this.targetClass = targetClass;
  }

  @Override
  public T initialise(CaseContext context, JsonNode narrowedInput) {
    return MAPPER.convertValue(narrowedInput, targetClass);
  }

  @Override
  public JsonNode serialise(T context) {
    return MAPPER.valueToTree(context);
  }

  @Override
  public T deserialise(JsonNode payload) {
    return MAPPER.convertValue(payload, targetClass);
  }

  @Override
  public Class<T> contextType() {
    return targetClass;
  }
}
