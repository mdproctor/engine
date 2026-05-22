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

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.casehub.api.context.CaseContext;
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

@JsonDeserialize(as = CaseContextImpl.class)
public class CaseContextImpl implements CaseContext {

  private static final ObjectMapper mapper = new ObjectMapper();

  private final Map<String, Object> data = new LinkedHashMap<>();
  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private long version = 0L;

  public CaseContextImpl() {}

  public CaseContextImpl(Map<String, Object> initial) {
    if (initial != null) {
      data.putAll(initial);
    }
  }

  public CaseContextImpl(Map<String, Object> initial, long version) {
    if (initial != null) {
      data.putAll(initial);
    }
    this.version = version;
  }

  public CaseContextImpl(JsonNode asNode) {
    this(mapper.convertValue(asNode == null ? mapper.createObjectNode() : asNode, Map.class));
  }

  @JsonAnyGetter
  @Override
  public Map<String, Object> getData() {
    lock.readLock().lock();
    try {
      return new LinkedHashMap<>(data);
    } finally {
      lock.readLock().unlock();
    }
  }

  @JsonAnySetter
  @Override
  public CaseContext set(String key, Object value) {
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
      return mapper.convertValue(value, type);
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
  public Object computeIfAbsent(String key, Function<String, Object> mappingFunction) {
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
  public CaseContext update(String key, Function<Object, Object> updateFunction) {
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
        return list.stream().map(item -> mapper.convertValue(item, elementType)).toList();
      }
      return null;
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

  @SuppressWarnings("unchecked")
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
  @Override
  public CaseContext setPath(String path, Object value) {
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
  @SuppressWarnings("unchecked")
  public Optional<JsonNode> applyAndDiff(String path, Object value) {
    lock.writeLock().lock();
    try {
      JsonNode before = mapper.convertValue(data, JsonNode.class);

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

      JsonNode after = mapper.convertValue(data, JsonNode.class);
      JsonNode diff = JsonDiff.asJson(before, after);
      return diff.isEmpty() ? Optional.empty() : Optional.of(diff);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public CaseContext setAll(Map<String, Object> values) {
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
  public boolean contains(String key) {
    lock.readLock().lock();
    try {
      return data.containsKey(key);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public CaseContext remove(String key) {
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
  public CaseContext clear() {
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

  @JsonIgnore
  @Override
  public Set<String> getKeys() {
    lock.readLock().lock();
    try {
      return new HashSet<>(data.keySet());
    } finally {
      lock.readLock().unlock();
    }
  }

  @JsonIgnore
  @Override
  public boolean isEmpty() {
    lock.readLock().lock();
    try {
      return data.isEmpty();
    } finally {
      lock.readLock().unlock();
    }
  }

  @JsonIgnore
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
  public JsonNode asJsonNode() {
    lock.readLock().lock();
    try {
      return mapper.convertValue(data, JsonNode.class);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public CaseContext merge(CaseContext other) {
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
  public CaseContext snapshot() {
    lock.readLock().lock();
    try {
      return new CaseContextImpl(deepCopy(data), version);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public JsonNode diff(CaseContext other) {
    lock.readLock().lock();
    try {
      JsonNode thisNode = mapper.convertValue(this.data, JsonNode.class);
      JsonNode otherNode = mapper.convertValue(other.getData(), JsonNode.class);
      return JsonDiff.asJson(thisNode, otherNode);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public void applyDiff(JsonNode diff) {
    lock.writeLock().lock();
    try {
      JsonNode current = mapper.convertValue(data, JsonNode.class);
      JsonNode patched = JsonPatch.apply(diff, current);
      Map<String, Object> updated =
          mapper.convertValue(
              patched,
              mapper
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
  public long getVersion() {
    lock.readLock().lock();
    try {
      return version;
    } finally {
      lock.readLock().unlock();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> deepCopy(Map<String, Object> source) {
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof Map) {
        value = deepCopy((Map<String, Object>) value);
      } else if (value instanceof List) {
        value = new ArrayList<>((List<?>) value);
      }
      copy.put(entry.getKey(), value);
    }
    return copy;
  }

  @Override
  public String toString() {
    lock.readLock().lock();
    try {
      return mapper.writeValueAsString(data);
    } catch (Exception e) {
      return data.toString();
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CaseContextImpl that)) {
      return false;
    }
    Map<String, Object> thisData = this.getData();
    Map<String, Object> thatData = that.getData();
    return thisData.equals(thatData);
  }

  @Override
  public int hashCode() {
    lock.readLock().lock();
    try {
      return data.hashCode();
    } finally {
      lock.readLock().unlock();
    }
  }
}
