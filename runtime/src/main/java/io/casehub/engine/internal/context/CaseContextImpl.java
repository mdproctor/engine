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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.CaseContextStoreFactory;
import io.casehub.api.context.ContextChangeEvent;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.MutableCaseContext;
import io.casehub.api.context.ReadableLayer;
import io.casehub.api.context.Subscription;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jboss.logging.Logger;

@JsonDeserialize(as = CaseContextImpl.class)
public class CaseContextImpl implements MutableCaseContext {

  private static final Logger LOG = Logger.getLogger(CaseContextImpl.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<String, WritableLayerImpl> layers = new LinkedHashMap<>();
  private final CaseContextStoreFactory storeFactory;
  private final UUID caseId;

  // ── Listener infrastructure ────────────────────────────────────────────────

  @JsonIgnore
  private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<ContextChangeEvent>>>
      keyListeners = new ConcurrentHashMap<>();

  @JsonIgnore
  private final CopyOnWriteArrayList<Consumer<ContextChangeEvent>> anyChangeListeners =
      new CopyOnWriteArrayList<>();

  // ── Constructors ────────────────────────────────────────────────────────────

  public CaseContextImpl() {
    this(InMemoryCaseContextStoreFactory.INSTANCE, null);
  }

  public CaseContextImpl(Map<String, Object> initial) {
    this.storeFactory = InMemoryCaseContextStoreFactory.INSTANCE;
    this.caseId = null;
    layers.put(
        ContextLayer.WORKING,
        new WritableLayerImpl(ContextLayer.WORKING, initial != null ? initial : Map.of()));
    layers.put(ContextLayer.SEMANTIC, new WritableLayerImpl(ContextLayer.SEMANTIC));
    layers.put(ContextLayer.EPISODIC, new WritableLayerImpl(ContextLayer.EPISODIC));
  }

  public CaseContextImpl(CaseContextStoreFactory storeFactory, UUID caseId) {
    this.storeFactory = storeFactory;
    this.caseId = caseId;
    initBuiltinLayers();
  }

  public CaseContextImpl(Map<String, Object> initial, long ignoredVersion) {
    // version is per-layer now; the top-level version param is ignored (legacy)
    this(initial);
  }

  @SuppressWarnings("unchecked")
  public CaseContextImpl(JsonNode asNode) {
    this(
        asNode == null
            ? null
            : (Map<String, Object>)
                MAPPER.convertValue(
                    asNode,
                    MAPPER
                        .getTypeFactory()
                        .constructMapType(LinkedHashMap.class, String.class, Object.class)));
  }

  private void initBuiltinLayers() {
    layers.put(
        ContextLayer.WORKING,
        new WritableLayerImpl(
            ContextLayer.WORKING, storeFactory.createStore(ContextLayer.WORKING, caseId)));
    layers.put(
        ContextLayer.SEMANTIC,
        new WritableLayerImpl(
            ContextLayer.SEMANTIC, storeFactory.createStore(ContextLayer.SEMANTIC, caseId)));
    layers.put(
        ContextLayer.EPISODIC,
        new WritableLayerImpl(
            ContextLayer.EPISODIC, storeFactory.createStore(ContextLayer.EPISODIC, caseId)));
  }

  private WritableLayerImpl working() {
    return layers.get(ContextLayer.WORKING);
  }

  // ── Layer access ────────────────────────────────────────────────────────────

  @Override
  public ReadableLayer layer(String name) {
    return layers.computeIfAbsent(
        name, n -> new WritableLayerImpl(n, storeFactory.createStore(n, caseId)));
  }

  /** Engine-internal: returns writable view (engine writes to semantic/episodic this way). */
  public WritableLayerImpl writableLayer(String name) {
    return layers.computeIfAbsent(
        name, n -> new WritableLayerImpl(n, storeFactory.createStore(n, caseId)));
  }

  /** Freezes a layer (makes it read-only). */
  public void freezeLayer(String name) {
    WritableLayerImpl p = layers.get(name);
    if (p != null) p.freeze();
  }

  // ── Listener registration ───────────────────────────────────────────────────

  @Override
  public Subscription onChange(String key, Consumer<ContextChangeEvent> listener) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(listener, "listener");
    CopyOnWriteArrayList<Consumer<ContextChangeEvent>> list =
        keyListeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
    list.add(listener);
    return () -> list.remove(listener);
  }

  @Override
  public Subscription onAnyChange(Consumer<ContextChangeEvent> listener) {
    Objects.requireNonNull(listener, "listener");
    anyChangeListeners.add(listener);
    return () -> anyChangeListeners.remove(listener);
  }

  private boolean hasListeners() {
    return !keyListeners.isEmpty() || !anyChangeListeners.isEmpty();
  }

  /** Fires per-key listeners first, then any-change listeners. Each in try/catch. */
  private void fireListeners(String key, Object oldValue, Object newValue) {
    ContextChangeEvent event = new ContextChangeEvent(key, oldValue, newValue);
    CopyOnWriteArrayList<Consumer<ContextChangeEvent>> perKey = keyListeners.get(key);
    if (perKey != null) {
      for (Consumer<ContextChangeEvent> listener : perKey) {
        try {
          listener.accept(event);
        } catch (Exception e) {
          LOG.warnf(e, "Context change listener threw for key '%s'", key);
        }
      }
    }
    for (Consumer<ContextChangeEvent> listener : anyChangeListeners) {
      try {
        listener.accept(event);
      } catch (Exception e) {
        LOG.warnf(e, "Any-change context listener threw for key '%s'", key);
      }
    }
  }

  // ── Flat API — delegates to working layer ──────────────────────────────────

  @JsonAnySetter
  @Override
  public CaseContext set(String key, Object value) {
    if (!hasListeners()) {
      working().set(key, value);
    } else {
      Object prev =
          working()
              .modify(
                  (store, changed) -> {
                    Object p = store.get(key);
                    if (!Objects.equals(p, value)) {
                      store.put(key, value);
                      changed.run();
                    }
                    return p;
                  });
      if (!Objects.equals(prev, value)) {
        fireListeners(key, prev, value);
      }
    }
    return this;
  }

  @Override
  public Object get(String key) {
    return working().get(key);
  }

  @Override
  public <T> T getAs(String key, Class<T> type) {
    return working().getAs(key, type);
  }

  @Override
  public <T> T getOrDefault(String key, T defaultValue) {
    return working().getOrDefault(key, defaultValue);
  }

  @Override
  public Object computeIfAbsent(String key, Function<String, Object> mappingFunction) {
    if (!hasListeners()) {
      return working().computeIfAbsent(key, mappingFunction);
    }
    Object[] result =
        working()
            .modify(
                (store, changed) -> {
                  Object v = store.get(key);
                  if (v == null) {
                    v = mappingFunction.apply(key);
                    if (v != null) {
                      store.put(key, v);
                      changed.run();
                      return new Object[] {Boolean.FALSE, v};
                    }
                    return new Object[] {Boolean.TRUE, null};
                  }
                  return new Object[] {Boolean.TRUE, v};
                });
    boolean existed = (Boolean) result[0];
    Object value = result[1];
    if (!existed && value != null) {
      fireListeners(key, null, value);
    }
    return value;
  }

  @Override
  public Object putIfAbsent(String key, Object value) {
    if (!hasListeners()) {
      return working().putIfAbsent(key, value);
    }
    Object[] result =
        working()
            .modify(
                (store, changed) -> {
                  Object existing = store.get(key);
                  if (existing == null) {
                    store.put(key, value);
                    changed.run();
                    return new Object[] {null, Boolean.TRUE};
                  }
                  return new Object[] {existing, Boolean.FALSE};
                });
    Object previous = result[0];
    boolean wasAbsent = (Boolean) result[1];
    if (wasAbsent) {
      fireListeners(key, null, value);
    }
    return previous;
  }

  @Override
  public boolean compareAndSet(String key, Object expected, Object newValue) {
    if (!hasListeners()) {
      return working().compareAndSet(key, expected, newValue);
    }
    Object[] result =
        working()
            .modify(
                (store, changed) -> {
                  Object current = store.get(key);
                  if (Objects.equals(current, expected)) {
                    if (!Objects.equals(current, newValue)) {
                      store.put(key, newValue);
                      changed.run();
                    }
                    return new Object[] {current, Boolean.TRUE};
                  }
                  return new Object[] {current, Boolean.FALSE};
                });
    Object oldValue = result[0];
    boolean swapped = (Boolean) result[1];
    if (swapped && !Objects.equals(oldValue, newValue)) {
      fireListeners(key, oldValue, newValue);
    }
    return swapped;
  }

  @Override
  public CaseContext update(String key, Function<Object, Object> updateFunction) {
    if (!hasListeners()) {
      working().update(key, updateFunction);
    } else {
      Object[] result =
          working()
              .modify(
                  (store, changed) -> {
                    Object current = store.get(key);
                    Object nv = updateFunction.apply(current);
                    if (nv != null) {
                      if (!Objects.equals(current, nv)) {
                        store.put(key, nv);
                        changed.run();
                      }
                    } else if (store.containsKey(key)) {
                      store.remove(key);
                      changed.run();
                    }
                    return new Object[] {current, nv};
                  });
      Object oldValue = result[0];
      Object newValue = result[1];
      if (!Objects.equals(oldValue, newValue)) {
        fireListeners(key, oldValue, newValue);
      }
    }
    return this;
  }

  @Override
  public String getString(String key) {
    return working().getString(key);
  }

  @Override
  public Integer getInt(String key) {
    return working().getInt(key);
  }

  @Override
  public Long getLong(String key) {
    return working().getLong(key);
  }

  @Override
  public Double getDouble(String key) {
    return working().getDouble(key);
  }

  @Override
  public Boolean getBoolean(String key) {
    return working().getBoolean(key);
  }

  @Override
  public <T> List<T> getList(String key, Class<T> elementType) {
    return working().getList(key, elementType);
  }

  @Override
  public Object getPath(String path) {
    return working().getPath(path);
  }

  @Override
  public String getPathAsString(String path) {
    return working().getPathAsString(path);
  }

  @SuppressWarnings("unchecked")
  @Override
  public CaseContext setPath(String path, Object value) {
    if (!hasListeners()) {
      working().setPath(path, value);
    } else {
      String topKey = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
      Object prev =
          working()
              .modify(
                  (store, changed) -> {
                    String[] parts = path.split("[.]");
                    if (parts.length == 1) {
                      Object p = store.get(parts[0]);
                      if (!Objects.equals(p, value)) {
                        store.put(parts[0], value);
                        changed.run();
                      }
                      return p;
                    }
                    Object rootValue = store.get(parts[0]);
                    Map<String, Object> current;
                    if (rootValue == null) {
                      rootValue = new LinkedHashMap<String, Object>();
                      current = (Map<String, Object>) rootValue;
                    } else if (rootValue instanceof Map) {
                      current = (Map<String, Object>) rootValue;
                    } else {
                      throw new IllegalStateException(
                          "Cannot set path: " + parts[0] + " is not a Map");
                    }
                    for (int i = 1; i < parts.length - 1; i++) {
                      Object next = current.get(parts[i]);
                      if (next == null) {
                        next = new LinkedHashMap<String, Object>();
                        current.put(parts[i], next);
                      }
                      if (next instanceof Map) {
                        current = (Map<String, Object>) next;
                      } else {
                        throw new IllegalStateException(
                            "Cannot set path: " + parts[i] + " is not a Map");
                      }
                    }
                    String leaf = parts[parts.length - 1];
                    Object p = current.get(leaf);
                    if (!Objects.equals(p, value)) {
                      current.put(leaf, value);
                      store.put(parts[0], rootValue);
                      changed.run();
                    }
                    return p;
                  });
      if (!Objects.equals(prev, value)) {
        fireListeners(topKey, prev, value);
      }
    }
    return this;
  }

  @Override
  public CaseContext setAll(Map<String, Object> values) {
    if (!hasListeners()) {
      working().setAll(values);
    } else if (values != null && !values.isEmpty()) {
      Map<String, Object> changed =
          working()
              .modify(
                  (store, markChanged) -> {
                    Map<String, Object> diff = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : values.entrySet()) {
                      Object prev = store.get(e.getKey());
                      if (!Objects.equals(prev, e.getValue())) {
                        store.put(e.getKey(), e.getValue());
                        diff.put(e.getKey(), prev);
                      }
                    }
                    if (!diff.isEmpty()) {
                      markChanged.run();
                    }
                    return diff;
                  });
      for (Map.Entry<String, Object> entry : changed.entrySet()) {
        fireListeners(entry.getKey(), entry.getValue(), values.get(entry.getKey()));
      }
    }
    return this;
  }

  @Override
  public Map<String, Object> getAll(String... keys) {
    return working().getAll(keys);
  }

  @Override
  public boolean contains(String key) {
    return working().contains(key);
  }

  @Override
  public CaseContext remove(String key) {
    if (!hasListeners()) {
      working().remove(key);
    } else {
      Object prev =
          working()
              .modify(
                  (store, changed) -> {
                    if (store.containsKey(key)) {
                      changed.run();
                      return store.remove(key);
                    }
                    return null;
                  });
      if (prev != null) {
        fireListeners(key, prev, null);
      }
    }
    return this;
  }

  @Override
  public CaseContext clear() {
    if (!hasListeners()) {
      working().clear();
    } else {
      Map<String, Object> prev =
          working()
              .modify(
                  (store, changed) -> {
                    if (!store.isEmpty()) {
                      Map<String, Object> snapshot = store.snapshot();
                      store.clear();
                      changed.run();
                      return snapshot;
                    }
                    return Map.<String, Object>of();
                  });
      for (Map.Entry<String, Object> entry : prev.entrySet()) {
        fireListeners(entry.getKey(), entry.getValue(), null);
      }
    }
    return this;
  }

  @JsonIgnore
  @Override
  public Set<String> getKeys() {
    return working().getKeys();
  }

  @JsonIgnore
  @Override
  public boolean isEmpty() {
    return working().isEmpty();
  }

  @JsonIgnore
  @Override
  public int size() {
    return working().size();
  }

  @JsonAnyGetter
  @Override
  public Map<String, Object> getData() {
    return working().getData();
  }

  // ── Multi-layer operations ─────────────────────────────────────────────────

  @Override
  public long getVersion() {
    return working().getVersion();
  }

  @Override
  public CaseContext merge(CaseContext other) {
    if (other == null) return this;
    Map<String, Object> otherData = other.getData();
    if (!hasListeners()) {
      working().setAll(otherData);
    } else if (!otherData.isEmpty()) {
      Map<String, Object> changed =
          working()
              .modify(
                  (store, markChanged) -> {
                    Map<String, Object> diff = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : otherData.entrySet()) {
                      Object prev = store.get(e.getKey());
                      if (!Objects.equals(prev, e.getValue())) {
                        store.put(e.getKey(), e.getValue());
                        diff.put(e.getKey(), prev);
                      }
                    }
                    if (!diff.isEmpty()) {
                      markChanged.run();
                    }
                    return diff;
                  });
      for (Map.Entry<String, Object> entry : changed.entrySet()) {
        fireListeners(entry.getKey(), entry.getValue(), otherData.get(entry.getKey()));
      }
    }
    return this;
  }

  @Override
  public JsonNode diff(CaseContext other) {
    return working().diff(other.layer(ContextLayer.WORKING)); // working layers only
  }

  @Override
  public void applyDiff(JsonNode diff) {
    working().applyDiff(diff); // working layer, working-layer-relative patches
  }

  @Override
  public Optional<JsonNode> applyAndDiff(String path, Object value) {
    return working().applyAndDiff(path, value); // working layer, path is working-relative
  }

  @Override
  public CaseContext snapshot() {
    // Deep-copy ALL layers
    CaseContextImpl snap = new CaseContextImpl();
    snap.layers.clear();
    for (Map.Entry<String, WritableLayerImpl> e : layers.entrySet()) {
      snap.layers.put(e.getKey(), e.getValue().deepCopy());
    }
    return snap;
  }

  @Override
  public JsonNode asJsonNode() {
    // Returns FULL layer document: {"working":{...},"semantic":{...},"episodic":{...},...}
    ObjectNode doc = MAPPER.createObjectNode();
    for (Map.Entry<String, WritableLayerImpl> e : layers.entrySet()) {
      doc.set(e.getKey(), e.getValue().asJsonNode());
    }
    return doc;
  }

  /**
   * Factory for recovery — reconstructs from a stored layer document. The layer document is the
   * output of {@link #asJsonNode()}: a JSON object whose keys are layer names and values are layer
   * data objects.
   */
  public static CaseContextImpl fromLayerDocument(JsonNode layerDoc) {
    CaseContextImpl ctx = new CaseContextImpl();
    if (layerDoc == null || layerDoc.isNull()) return ctx;
    layerDoc
        .fields()
        .forEachRemaining(
            entry -> {
              String name = entry.getKey();
              @SuppressWarnings("unchecked")
              Map<String, Object> data = MAPPER.convertValue(entry.getValue(), Map.class);
              ctx.layers.put(name, new WritableLayerImpl(name, data));
            });
    return ctx;
  }

  // ── equals / hashCode / toString — working layer data for backward compat ──

  public void close() {
    for (WritableLayerImpl layer : layers.values()) {
      try {
        layer.getStore().close();
      } catch (Exception e) {
        LOG.warnf(e, "Failed to close store for layer %s", layer.layerName());
      }
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof CaseContextImpl that)) return false;
    return this.getData().equals(that.getData());
  }

  @Override
  public int hashCode() {
    return getData().hashCode();
  }

  @Override
  public String toString() {
    try {
      return MAPPER.writeValueAsString(getData());
    } catch (Exception e) {
      return getData().toString();
    }
  }
}
