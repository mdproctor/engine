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
package io.casehub.api.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ConflictResolver {

  public static final String LAST_WRITER_WINS = "LAST_WRITER_WINS";
  public static final String FIRST_WRITER_WINS = "FIRST_WRITER_WINS";
  public static final String FAIL = "FAIL";
  public static final String DEEP_MERGE = "DEEP_MERGE";

  private ConflictResolver() {}

  public static Object resolve(String strategy, String key, Object existing, Object incoming) {
    if (existing == null) return incoming;
    if (strategy == null) return incoming;
    return switch (strategy) {
      case FIRST_WRITER_WINS -> existing;
      case FAIL ->
          throw new IllegalStateException(
              "Conflicting writes to key '" + key + "' — binding uses FAIL strategy");
      case DEEP_MERGE -> deepMerge(existing, incoming);
      default -> incoming;
    };
  }

  @SuppressWarnings("unchecked")
  private static Object deepMerge(Object existing, Object incoming) {
    if (existing instanceof Map<?, ?> existingMap && incoming instanceof Map<?, ?> incomingMap) {
      Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) existingMap);
      for (Map.Entry<String, Object> entry : ((Map<String, Object>) incomingMap).entrySet()) {
        merged.merge(entry.getKey(), entry.getValue(), (old, val) -> deepMerge(old, val));
      }
      return merged;
    }
    return incoming;
  }
}
