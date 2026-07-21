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
package io.casehub.engine.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextChangeEvent;
import io.casehub.api.context.ReadableLayer;
import io.casehub.api.context.Subscription;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

class MapCaseContext implements CaseContext {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Map<String, Object> data;

  MapCaseContext(Map<String, Object> data) {
    this.data = new HashMap<>(data);
  }

  @Override
  public ReadableLayer layer(String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Map<String, Object> getData() {
    return Collections.unmodifiableMap(data);
  }

  @Override
  public CaseContext set(String key, Object value) {
    data.put(key, value);
    return this;
  }

  @Override
  public Object get(String key) {
    return data.get(key);
  }

  @Override
  public <T> T getAs(String key, Class<T> type) {
    return type.cast(data.get(key));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T getOrDefault(String key, T defaultValue) {
    Object v = data.get(key);
    return v != null ? (T) v : defaultValue;
  }

  @Override
  public Object computeIfAbsent(String key, Function<String, Object> mappingFunction) {
    return data.computeIfAbsent(key, mappingFunction);
  }

  @Override
  public Object putIfAbsent(String key, Object value) {
    return data.putIfAbsent(key, value);
  }

  @Override
  public boolean compareAndSet(String key, Object expected, Object newValue) {
    if (java.util.Objects.equals(data.get(key), expected)) {
      data.put(key, newValue);
      return true;
    }
    return false;
  }

  @Override
  public CaseContext update(String key, Function<Object, Object> updateFunction) {
    data.compute(key, (k, v) -> updateFunction.apply(v));
    return this;
  }

  @Override
  public String getString(String key) {
    Object v = data.get(key);
    return v instanceof String s ? s : null;
  }

  @Override
  public Integer getInt(String key) {
    Object v = data.get(key);
    return v instanceof Number n ? n.intValue() : null;
  }

  @Override
  public Long getLong(String key) {
    Object v = data.get(key);
    return v instanceof Number n ? n.longValue() : null;
  }

  @Override
  public Double getDouble(String key) {
    Object v = data.get(key);
    return v instanceof Number n ? n.doubleValue() : null;
  }

  @Override
  public Boolean getBoolean(String key) {
    Object v = data.get(key);
    return v instanceof Boolean b ? b : null;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> List<T> getList(String key, Class<T> elementType) {
    Object v = data.get(key);
    return v instanceof List ? (List<T>) v : List.of();
  }

  @Override
  @SuppressWarnings("unchecked")
  public Object getPath(String path) {
    String[] parts = path.split("\\.");
    Object current = data;
    for (String part : parts) {
      if (current instanceof Map<?, ?> map) {
        current = map.get(part);
      } else {
        return null;
      }
    }
    return current;
  }

  @Override
  public String getPathAsString(String path) {
    Object v = getPath(path);
    return v instanceof String s ? s : (v != null ? v.toString() : null);
  }

  @Override
  public CaseContext setPath(String path, Object value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<JsonNode> applyAndDiff(String path, Object value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CaseContext setAll(Map<String, Object> values) {
    data.putAll(values);
    return this;
  }

  @Override
  public Map<String, Object> getAll(String... keys) {
    Map<String, Object> result = new HashMap<>();
    for (String k : keys) {
      if (data.containsKey(k)) result.put(k, data.get(k));
    }
    return result;
  }

  @Override
  public boolean contains(String key) {
    return data.containsKey(key);
  }

  @Override
  public CaseContext remove(String key) {
    data.remove(key);
    return this;
  }

  @Override
  public CaseContext clear() {
    data.clear();
    return this;
  }

  @Override
  public Set<String> getKeys() {
    return data.keySet();
  }

  @Override
  public boolean isEmpty() {
    return data.isEmpty();
  }

  @Override
  public int size() {
    return data.size();
  }

  @Override
  public JsonNode asJsonNode() {
    return MAPPER.valueToTree(data);
  }

  @Override
  public CaseContext merge(CaseContext other) {
    data.putAll(other.getData());
    return this;
  }

  @Override
  public CaseContext snapshot() {
    return new MapCaseContext(new HashMap<>(data));
  }

  @Override
  public JsonNode diff(CaseContext other) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void applyDiff(JsonNode diff) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getVersion() {
    return 0;
  }

  @Override
  public Subscription onChange(String key, Consumer<ContextChangeEvent> listener) {
    return () -> {};
  }

  @Override
  public Subscription onAnyChange(Consumer<ContextChangeEvent> listener) {
    return () -> {};
  }
}
