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
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.ReadableLayer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@JsonDeserialize(as = CaseContextImpl.class)
public class CaseContextImpl implements CaseContext {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<String, WritableLayerImpl> layers = new LinkedHashMap<>();

  // ── Constructors ────────────────────────────────────────────────────────────

  public CaseContextImpl() {
    initBuiltinLayers();
  }

  public CaseContextImpl(Map<String, Object> initial) {
    // Use WritableLayerImpl(name, data) constructor which pre-populates without incrementing
    // version,
    // preserving the original contract that the map constructor does not increment version.
    layers.put(
        ContextLayer.WORKING,
        new WritableLayerImpl(ContextLayer.WORKING, initial != null ? initial : Map.of()));
    layers.put(ContextLayer.SEMANTIC, new WritableLayerImpl(ContextLayer.SEMANTIC));
    layers.put(ContextLayer.EPISODIC, new WritableLayerImpl(ContextLayer.EPISODIC));
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
    layers.put(ContextLayer.WORKING, new WritableLayerImpl(ContextLayer.WORKING));
    layers.put(ContextLayer.SEMANTIC, new WritableLayerImpl(ContextLayer.SEMANTIC));
    layers.put(ContextLayer.EPISODIC, new WritableLayerImpl(ContextLayer.EPISODIC));
  }

  private WritableLayerImpl working() {
    return layers.get(ContextLayer.WORKING);
  }

  // ── Layer access ────────────────────────────────────────────────────────────

  @Override
  public ReadableLayer layer(String name) {
    return layers.computeIfAbsent(name, WritableLayerImpl::new);
  }

  /** Engine-internal: returns writable view (engine writes to semantic/episodic this way). */
  public WritableLayerImpl writableLayer(String name) {
    return layers.computeIfAbsent(name, WritableLayerImpl::new);
  }

  /** Freezes a layer (makes it read-only). */
  public void freezeLayer(String name) {
    WritableLayerImpl p = layers.get(name);
    if (p != null) p.freeze();
  }

  // ── Flat API — delegates to working layer ──────────────────────────────────

  @JsonAnySetter
  @Override
  public CaseContext set(String key, Object value) {
    working().set(key, value);
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
    return working().computeIfAbsent(key, mappingFunction);
  }

  @Override
  public Object putIfAbsent(String key, Object value) {
    return working().putIfAbsent(key, value);
  }

  @Override
  public boolean compareAndSet(String key, Object expected, Object newValue) {
    return working().compareAndSet(key, expected, newValue);
  }

  @Override
  public CaseContext update(String key, Function<Object, Object> updateFunction) {
    working().update(key, updateFunction);
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

  @Override
  public CaseContext setPath(String path, Object value) {
    working().setPath(path, value);
    return this;
  }

  @Override
  public CaseContext setAll(Map<String, Object> values) {
    working().setAll(values);
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
    working().remove(key);
    return this;
  }

  @Override
  public CaseContext clear() {
    working().clear();
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
    working().setAll(other.getData()); // merge working layers only
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
