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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * Standard reference to externally-stored domain data.
 *
 * <p>{@code $dataRef} is a reserved top-level JSON key in the ContextBridge protocol. Domain
 * objects used with ContextBridge must not contain {@code $dataRef} as a top-level key. The {@code
 * $} prefix follows the JSON Schema convention for meta-properties ({@code $ref}, {@code $id},
 * {@code $schema}).
 *
 * <p>Stores the type name as a {@code String}, not a {@code Class<?>}. No {@code Class.forName()}
 * call occurs at deserialization time — type resolution is deferred to {@code
 * DataRefRegistry.resolve()}, which validates the type name against registered resolvers before any
 * class loading.
 *
 * @param source resolver identifier (e.g., "document-store", "ledger", "s3") — matches the {@code
 *     id()} of a {@link io.casehub.api.spi.DataRefResolver}
 * @param key the reference key within that source — opaque to the engine
 * @param typeName the fully qualified Java class name this resolves to
 */
public record DataRef<T>(String source, String key, String typeName) {

  public static final String DISCRIMINATOR = "$dataRef";

  public DataRef {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(typeName, "typeName");
  }

  public static <T> DataRef<T> of(String source, String key, Class<T> type) {
    return new DataRef<>(source, key, type.getName());
  }

  public static boolean isRef(JsonNode node) {
    return node != null && node.has(DISCRIMINATOR);
  }

  public static DataRef<?> fromJson(JsonNode node) {
    JsonNode ref = node.get(DISCRIMINATOR);
    if (ref == null || !ref.has("source") || !ref.has("key") || !ref.has("type")) {
      throw new IllegalArgumentException(
          "Malformed $dataRef: requires source, key, and type fields");
    }
    return new DataRef<>(
        ref.get("source").asText(), ref.get("key").asText(), ref.get("type").asText());
  }

  public JsonNode toJson(ObjectMapper mapper) {
    ObjectNode root = mapper.createObjectNode();
    ObjectNode ref = mapper.createObjectNode();
    ref.put("source", source);
    ref.put("key", key);
    ref.put("type", typeName);
    root.set(DISCRIMINATOR, ref);
    return root;
  }
}
