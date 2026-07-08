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
import io.casehub.api.context.ReadOnlyLayerException;
import io.casehub.api.context.ReadableLayer;
import io.casehub.api.context.WritableLayer;
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
import java.util.function.BiFunction;
import java.util.function.Function;

/** Thread-safe, versioned data store for a single CaseFile layer. */
public class WritableLayerImpl implements WritableLayer {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String layerName;
  private final Map<String, Object> data = new LinkedHashMap<>();
  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private long version = 0L;
  private volatile boolean frozen = false;

  public WritableLayerImpl(String layerName) {
    this.layerName = layerName;
  }

  public WritableLayerImpl(String layerName, Map<String, Object> initial) {
    this(layerName, initial, true);
  }

  private WritableLayerImpl(String layerName, Map<String, Object> initial, boolean deepCopy) {
    this.layerName = layerName;
    if (initial != null) {
      data.putAll(deepCopy ? deepCopyMap(initial) : initial);
    }
  }

  // ── ReadableLayer ─────────────────────────────────────────────────────────

  @Override
  public String layerName() {
    return layerName;
  }

  @Override
  public boolean isReadOnly() {
    return frozen;
  }

  public WritableLayerImpl freeze() {
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

  // ── WritableLayer ──────────────────────────────────────────────────────────

  @Override
  public WritableLayer set(String key, Object value) {
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
  public WritableLayer setAll(Map<String, Object> values) {
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
  public WritableLayer setPath(String path, Object value) {
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
  public WritableLayer remove(String key) {
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
  public WritableLayer clear() {
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
  public WritableLayer merge(ReadableLayer other) {
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
  public WritableLayer update(String key, Function<Object, Object> updateFunction) {
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
  public JsonNode diff(ReadableLayer other) {
    lock.readLock().lock();
    try {
      JsonNode thisNode = MAPPER.convertValue(this.data, JsonNode.class);
      JsonNode otherNode = MAPPER.convertValue(other.getData(), JsonNode.class);
      return JsonDiff.asJson(thisNode, otherNode);
    } finally {
      lock.readLock().unlock();
    }
  }

  // ── Atomic read-modify-write primitive (package-private) ────────────────────

  /**
   * Executes an atomic read-modify-write operation under the write lock. The action receives the
   * underlying data map and a {@code markChanged} callback; call it when the action mutates data so
   * the version counter stays correct. Returns whatever the action returns.
   *
   * <p>Used by {@link CaseContextImpl} to capture previous values for listener notification without
   * requiring a dedicated {@code *Prev()} variant of every mutating method.
   */
  <R> R modify(BiFunction<Map<String, Object>, Runnable, R> action) {
    checkWritable();
    lock.writeLock().lock();
    try {
      boolean[] changed = {false};
      R result = action.apply(data, () -> changed[0] = true);
      if (changed[0]) {
        version++;
      }
      return result;
    } finally {
      lock.writeLock().unlock();
    }
  }

  // ── Public helpers ─────────────────────────────────────────────────────────

  /**
   * Engine-internal write that bypasses the frozen check. Used by {@code EpisodicLayerUpdater} to
   * update engine-managed layers (episodic) without exposing an unfreeze/refreeze cycle.
   */
  public WritableLayerImpl engineSet(String key, Object value) {
    lock.writeLock().lock();
    try {
      Object prev = data.get(key);
      if (!Objects.equals(prev, value)) {
        data.put(key, value);
        // Intentionally does NOT increment version — episodic writes are engine-managed and must
        // not trigger working-layer version bumps observed by trigger evaluation.
      }
      return this;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Engine-internal atomic read-modify-write that bypasses the frozen check. Holds the write lock
   * for the entire read-modify-write sequence, preventing lost updates under concurrent writes.
   * Does NOT increment version — engine-internal, must not trigger binding re-evaluation.
   */
  public void engineUpdate(String key, java.util.function.UnaryOperator<Object> updater) {
    lock.writeLock().lock();
    try {
      Object current = data.get(key);
      Object updated = updater.apply(current);
      if (updated != null) {
        data.put(key, updated);
      } else if (data.containsKey(key)) {
        data.remove(key);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public ReadableLayer snapshot() {
    return deepCopy();
  }

  /**
   * Returns a deep copy of this layer, detached from the original. The copy shares the same
   * layerName but has an independent data map.
   */
  public WritableLayerImpl deepCopy() {
    lock.readLock().lock();
    try {
      return new WritableLayerImpl(layerName, deepCopyMap(data), false);
    } finally {
      lock.readLock().unlock();
    }
  }

  // ── Private helpers ────────────────────────────────────────────────────────

  private void checkWritable() {
    if (frozen) throw new ReadOnlyLayerException(layerName);
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
    for (var e : source.entrySet()) {
      Object v = e.getValue();
      if (v instanceof Map) {
        v = deepCopyMap((Map<String, Object>) v);
      } else if (v instanceof List) {
        List<?> original = (List<?>) v;
        List<Object> listCopy = new ArrayList<>(original.size());
        for (Object item : original) {
          if (item instanceof Map) {
            listCopy.add(deepCopyMap((Map<String, Object>) item));
          } else {
            listCopy.add(item);
          }
        }
        v = listCopy;
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
