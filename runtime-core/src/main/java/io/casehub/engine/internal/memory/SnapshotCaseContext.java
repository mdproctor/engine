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
package io.casehub.engine.internal.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.ReadableLayer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Read-only {@link CaseContext} backed by a Map snapshot from {@code
 * CaseOutcomeEvent.caseFileSnapshot()}.
 *
 * <p>Only the WORKING layer is populated (from {@code caseFileSnapshot}). All other layer accessors
 * return empty data. Mutation methods throw {@link UnsupportedOperationException}.
 *
 * <p>Lambda feature extractors that access non-WORKING layers will receive empty results at retain
 * time — this is inherent to retain-time context where only the final working layer snapshot is
 * available after case close.
 */
final class SnapshotCaseContext implements CaseContext {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<String, Object> data;
  private final ReadableLayer workingLayer;
  private final ReadableLayer emptyLayer;

  SnapshotCaseContext(Map<String, Object> snapshot) {
    this.data = Map.copyOf(Objects.requireNonNull(snapshot));
    this.workingLayer = new SnapshotReadableLayer(ContextLayer.WORKING, this.data);
    this.emptyLayer = new SnapshotReadableLayer("empty", Map.of());
  }

  @Override
  public ReadableLayer layer(String name) {
    return ContextLayer.WORKING.equals(name) ? workingLayer : emptyLayer;
  }

  @Override
  public Map<String, Object> getData() {
    return data;
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
  public <T> T getOrDefault(String key, T defaultValue) {
    @SuppressWarnings("unchecked")
    T value = (T) data.get(key);
    return value != null ? value : defaultValue;
  }

  @Override
  public String getString(String key) {
    Object v = data.get(key);
    return v != null ? v.toString() : null;
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
  public <T> List<T> getList(String key, Class<T> elementType) {
    return List.of();
  }

  @Override
  public Object getPath(String path) {
    return null;
  }

  @Override
  public String getPathAsString(String path) {
    return null;
  }

  @Override
  public boolean contains(String key) {
    return data.containsKey(key);
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
  public Map<String, Object> getAll(String... keys) {
    return Map.of();
  }

  @Override
  public long getVersion() {
    return 0;
  }

  @Override
  public CaseContext snapshot() {
    return this;
  }

  @Override
  public JsonNode diff(CaseContext other) {
    return MAPPER.createObjectNode();
  }

  // --- Mutation methods: all throw ---

  @Override
  public CaseContext set(String key, Object value) {
    throw new UnsupportedOperationException("SnapshotCaseContext is read-only");
  }

  @Override
  public Object computeIfAbsent(String key, Function<String, Object> fn) {
    throw new UnsupportedOperationException("SnapshotCaseContext is read-only");
  }

  @Override
  public Object putIfAbsent(String key, Object value) {
    throw new UnsupportedOperationException("SnapshotCaseContext is read-only");
  }

  @Override
  public boolean compareAndSet(String key, Object expected, Object newValue) {
    throw new UnsupportedOperationException("SnapshotCaseContext is read-only");
  }

  @Override
  public CaseContext update(String key, Function<Object, Object> fn) {
    throw new UnsupportedOperationException("SnapshotCaseContext is read-only");
  }

  @Override
  public CaseContext setPath(String path, Object value) {
    throw new UnsupportedOperationException("SnapshotCaseContext is read-only");
  }

  @Override
  public Optional<JsonNode> applyAndDiff(String path, Object value) {
    throw new UnsupportedOperationException("SnapshotCaseContext is read-only");
  }

  @Override
  public CaseContext setAll(Map<String, Object> values) {
    throw new UnsupportedOperationException("SnapshotCaseContext is read-only");
  }

  @Override
  public CaseContext remove(String key) {
    throw new UnsupportedOperationException("SnapshotCaseContext is read-only");
  }

  @Override
  public CaseContext clear() {
    throw new UnsupportedOperationException("SnapshotCaseContext is read-only");
  }

  @Override
  public void applyDiff(JsonNode diff) {
    throw new UnsupportedOperationException("SnapshotCaseContext is read-only");
  }

  @Override
  public CaseContext merge(CaseContext other) {
    throw new UnsupportedOperationException("SnapshotCaseContext is read-only");
  }

  private record SnapshotReadableLayer(String layerName, Map<String, Object> data)
      implements ReadableLayer {

    @Override
    public boolean isReadOnly() {
      return true;
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
    public <T> T getOrDefault(String key, T defaultValue) {
      @SuppressWarnings("unchecked")
      T value = (T) data.get(key);
      return value != null ? value : defaultValue;
    }

    @Override
    public String getString(String key) {
      Object v = data.get(key);
      return v != null ? v.toString() : null;
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
    public <T> List<T> getList(String key, Class<T> elementType) {
      return List.of();
    }

    @Override
    public boolean contains(String key) {
      return data.containsKey(key);
    }

    @Override
    public Set<String> getKeys() {
      return data.keySet();
    }

    @Override
    public Map<String, Object> getData() {
      return data;
    }

    @Override
    public Map<String, Object> getAll(String... keys) {
      return Map.of();
    }

    @Override
    public Object getPath(String path) {
      return null;
    }

    @Override
    public String getPathAsString(String path) {
      return null;
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
    public long getVersion() {
      return 0;
    }

    @Override
    public JsonNode asJsonNode() {
      return MAPPER.valueToTree(data);
    }

    @Override
    public ReadableLayer snapshot() {
      return this;
    }
  }
}
