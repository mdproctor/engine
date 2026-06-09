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
package io.casehub.engine.internal.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.ReadOnlyPanelException;
import io.casehub.api.context.ReadablePanel;
import io.casehub.api.context.WritablePanel;
import io.fabric8.zjsonpatch.JsonDiff;
import io.fabric8.zjsonpatch.JsonPatch;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/** Thread-safe, versioned data store for a single CaseFile panel. */
public class WritablePanelImpl implements WritablePanel {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String panelName;
  private final Map<String, Object> data = new LinkedHashMap<>();
  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private long version = 0L;
  private volatile boolean frozen = false;

  public WritablePanelImpl(String panelName) {
    this.panelName = panelName;
  }

  public WritablePanelImpl(String panelName, Map<String, Object> initial) {
    this.panelName = panelName;
    if (initial != null) {
      data.putAll(initial);
    }
  }

  // ── ReadablePanel ─────────────────────────────────────────────────────────

  @Override
  public String panelName() {
    return panelName;
  }

  @Override
  public boolean isReadOnly() {
    return frozen;
  }

  public WritablePanelImpl freeze() {
    this.frozen = true;
    return this;
  }

  @Override
  public Object get(String key) {
    lock.readLock().lock();
    try {
      return data.get(key);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public <T> T getAs(String key, Class<T> type) {
    lock.readLock().lock();
    try {
      Object value = data.get(key);
      if (value == null) {
        return null;
      }
      if (type.isInstance(value)) {
        return type.cast(value);
      }
      return MAPPER.convertValue(value, type);
    } finally {
      lock.readLock().unlock();
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T getOrDefault(String key, T defaultValue) {
    lock.readLock().lock();
    try {
      Object value = data.get(key);
      return value != null ? (T) value : defaultValue;
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public String getString(String key) {
    Object v = get(key);
    return v != null ? v.toString() : null;
  }

  @Override
  public Integer getInt(String key) {
    Object v = get(key);
    if (v == null) {
      return null;
    }
    if (v instanceof Number n) {
      return n.intValue();
    }
    return Integer.parseInt(v.toString());
  }

  @Override
  public Long getLong(String key) {
    Object v = get(key);
    if (v == null) {
      return null;
    }
    if (v instanceof Number n) {
      return n.longValue();
    }
    return Long.parseLong(v.toString());
  }

  @Override
  public Double getDouble(String key) {
    Object v = get(key);
    if (v == null) {
      return null;
    }
    if (v instanceof Number n) {
      return n.doubleValue();
    }
    return Double.parseDouble(v.toString());
  }

  @Override
  public Boolean getBoolean(String key) {
    Object v = get(key);
    if (v == null) {
      return null;
    }
    if (v instanceof Boolean b) {
      return b;
    }
    return Boolean.parseBoolean(v.toString());
  }

  @Override
  public <T> List<T> getList(String key, Class<T> elementType) {
    lock.readLock().lock();
    try {
      Object v = data.get(key);
      if (v == null) {
        return null;
      }
      if (v instanceof List<?> list) {
        return list.stream().map(item -> MAPPER.convertValue(item, elementType)).toList();
      }
      return null;
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public boolean contains(String key) {
    lock.readLock().lock();
    try {
      return data.containsKey(key);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Set<String> getKeys() {
    lock.readLock().lock();
    try {
      return new HashSet<>(data.keySet());
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Map<String, Object> getData() {
    lock.readLock().lock();
    try {
      return new LinkedHashMap<>(data);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Map<String, Object> getAll(String... keys) {
    lock.readLock().lock();
    try {
      Map<String, Object> result = new LinkedHashMap<>();
      for (String key : keys) {
        Object value = data.get(key);
        if (value != null) {
          result.put(key, value);
        }
      }
      return result;
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Object getPath(String path) {
    lock.readLock().lock();
    try {
      return getPathInternal(path);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public String getPathAsString(String path) {
    Object v = getPath(path);
    return v != null ? v.toString() : null;
  }

  @Override
  public boolean isEmpty() {
    lock.readLock().lock();
    try {
      return data.isEmpty();
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public int size() {
    lock.readLock().lock();
    try {
      return data.size();
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public long getVersion() {
    lock.readLock().lock();
    try {
      return version;
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public JsonNode asJsonNode() {
    lock.readLock().lock();
    try {
      return MAPPER.convertValue(data, JsonNode.class);
    } finally {
      lock.readLock().unlock();
    }
  }

  // ── WritablePanel ──────────────────────────────────────────────────────────

  @Override
  public WritablePanel set(String key, Object value) {
    checkWritable();
    lock.writeLock().lock();
    try {
      Object prev = data.get(key);
      if (!Objects.equals(prev, value)) {
        data.put(key, value);
        version++;
      }
      return this;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public WritablePanel setAll(Map<String, Object> values) {
    checkWritable();
    if (values == null || values.isEmpty()) {
      return this;
    }
    lock.writeLock().lock();
    try {
      boolean changed = false;
      for (Map.Entry<String, Object> e : values.entrySet()) {
        Object prev = data.get(e.getKey());
        if (!Objects.equals(prev, e.getValue())) {
          data.put(e.getKey(), e.getValue());
          changed = true;
        }
      }
      if (changed) {
        version++;
      }
      return this;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public WritablePanel setPath(String path, Object value) {
    checkWritable();
    lock.writeLock().lock();
    try {
      String[] parts = path.split("\\.");
      Map<String, Object> current = data;
      for (int i = 0; i < parts.length - 1; i++) {
        Object next = current.get(parts[i]);
        if (next == null) {
          next = new LinkedHashMap<String, Object>();
          current.put(parts[i], next);
        }
        if (next instanceof Map) {
          current = (Map<String, Object>) next;
        } else {
          throw new IllegalStateException("Cannot set path: " + parts[i] + " is not a Map");
        }
      }
      String leaf = parts[parts.length - 1];
      Object prev = current.get(leaf);
      if (!Objects.equals(prev, value)) {
        current.put(leaf, value);
        version++;
      }
      return this;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public WritablePanel remove(String key) {
    checkWritable();
    lock.writeLock().lock();
    try {
      if (data.containsKey(key)) {
        data.remove(key);
        version++;
      }
      return this;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public WritablePanel clear() {
    checkWritable();
    lock.writeLock().lock();
    try {
      if (!data.isEmpty()) {
        data.clear();
        version++;
      }
      return this;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public WritablePanel merge(ReadablePanel other) {
    checkWritable();
    if (other == null) {
      return this;
    }
    lock.writeLock().lock();
    try {
      Map<String, Object> otherData = other.getData();
      boolean changed = false;
      for (Map.Entry<String, Object> e : otherData.entrySet()) {
        Object prev = data.get(e.getKey());
        if (!Objects.equals(prev, e.getValue())) {
          data.put(e.getKey(), e.getValue());
          changed = true;
        }
      }
      if (changed) {
        version++;
      }
      return this;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public Object computeIfAbsent(String key, Function<String, Object> mappingFunction) {
    checkWritable();
    lock.writeLock().lock();
    try {
      Object value = data.get(key);
      if (value == null) {
        value = mappingFunction.apply(key);
        if (value != null) {
          data.put(key, value);
          version++;
        }
      }
      return value;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public Object putIfAbsent(String key, Object value) {
    checkWritable();
    lock.writeLock().lock();
    try {
      Object existing = data.get(key);
      if (existing == null) {
        data.put(key, value);
        version++;
      }
      return existing;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public boolean compareAndSet(String key, Object expected, Object newValue) {
    checkWritable();
    lock.writeLock().lock();
    try {
      Object current = data.get(key);
      if (Objects.equals(current, expected)) {
        if (!Objects.equals(current, newValue)) {
          data.put(key, newValue);
          version++;
        }
        return true;
      }
      return false;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public WritablePanel update(String key, Function<Object, Object> updateFunction) {
    checkWritable();
    lock.writeLock().lock();
    try {
      Object current = data.get(key);
      Object newValue = updateFunction.apply(current);
      if (newValue != null) {
        if (!Objects.equals(current, newValue)) {
          data.put(key, newValue);
          version++;
        }
      } else {
        if (data.containsKey(key)) {
          data.remove(key);
          version++;
        }
      }
      return this;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public Optional<JsonNode> applyAndDiff(String path, Object value) {
    checkWritable();
    lock.writeLock().lock();
    try {
      JsonNode before = MAPPER.convertValue(data, JsonNode.class);

      String[] parts = path.split("\\.");
      Map<String, Object> current = data;
      for (int i = 0; i < parts.length - 1; i++) {
        Object next = current.get(parts[i]);
        if (next == null) {
          next = new LinkedHashMap<String, Object>();
          current.put(parts[i], next);
        }
        if (next instanceof Map) {
          current = (Map<String, Object>) next;
        } else {
          throw new IllegalStateException("Cannot set path: " + parts[i] + " is not a Map");
        }
      }
      String leaf = parts[parts.length - 1];
      Object prev = current.get(leaf);
      if (!Objects.equals(prev, value)) {
        current.put(leaf, value);
        version++;
      }

      JsonNode after = MAPPER.convertValue(data, JsonNode.class);
      JsonNode diff = JsonDiff.asJson(before, after);
      return diff.isEmpty() ? Optional.empty() : Optional.of(diff);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void applyDiff(JsonNode diff) {
    checkWritable();
    lock.writeLock().lock();
    try {
      JsonNode current = MAPPER.convertValue(data, JsonNode.class);
      JsonNode patched = JsonPatch.apply(diff, current);
      Map<String, Object> updated =
          MAPPER.convertValue(
              patched,
              MAPPER
                  .getTypeFactory()
                  .constructMapType(LinkedHashMap.class, String.class, Object.class));
      data.clear();
      data.putAll(updated);
      version++;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public JsonNode diff(ReadablePanel other) {
    lock.readLock().lock();
    try {
      JsonNode thisNode = MAPPER.convertValue(this.data, JsonNode.class);
      JsonNode otherNode = MAPPER.convertValue(other.getData(), JsonNode.class);
      return JsonDiff.asJson(thisNode, otherNode);
    } finally {
      lock.readLock().unlock();
    }
  }

  // ── Public helpers ─────────────────────────────────────────────────────────

  /**
   * Returns a deep copy of this panel, detached from the original. The copy shares the same
   * panelName but has an independent data map.
   */
  public WritablePanelImpl deepCopy() {
    lock.readLock().lock();
    try {
      return new WritablePanelImpl(panelName, deepCopyMap(data));
    } finally {
      lock.readLock().unlock();
    }
  }

  // ── Private helpers ────────────────────────────────────────────────────────

  private void checkWritable() {
    if (frozen) throw new ReadOnlyPanelException(panelName);
  }

  private Object getPathInternal(String path) {
    String[] parts = path.split("\\.");
    Object current = data;
    for (String part : parts) {
      if (current instanceof Map<?, ?> map) {
        current = map.get(part);
      } else {
        return null;
      }
      if (current == null) {
        return null;
      }
    }
    return current;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : source.entrySet()) {
      Object v = e.getValue();
      if (v instanceof Map) {
        v = deepCopyMap((Map<String, Object>) v);
      } else if (v instanceof List) {
        v = new ArrayList<>((List<?>) v);
      }
      copy.put(e.getKey(), v);
    }
    return copy;
  }

  @Override
  public String toString() {
    lock.readLock().lock();
    try {
      return MAPPER.writeValueAsString(data);
    } catch (Exception e) {
      return data.toString();
    } finally {
      lock.readLock().unlock();
    }
  }
}
