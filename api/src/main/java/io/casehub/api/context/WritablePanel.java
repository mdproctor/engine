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
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public interface WritablePanel extends ReadablePanel {

  WritablePanel set(String key, Object value);

  WritablePanel setAll(Map<String, Object> values);

  WritablePanel setPath(String path, Object value);

  WritablePanel remove(String key);

  WritablePanel clear();

  WritablePanel merge(ReadablePanel other);

  Object computeIfAbsent(String key, Function<String, Object> mappingFunction);

  Object putIfAbsent(String key, Object value);

  boolean compareAndSet(String key, Object expected, Object newValue);

  WritablePanel update(String key, Function<Object, Object> updateFunction);

  Optional<JsonNode> applyAndDiff(String path, Object value);

  void applyDiff(JsonNode diff);

  JsonNode diff(ReadablePanel other);
}
