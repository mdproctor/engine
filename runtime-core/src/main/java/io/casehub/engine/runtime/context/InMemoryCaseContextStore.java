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

import io.casehub.api.context.CaseContextStore;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class InMemoryCaseContextStore implements CaseContextStore {

  private final Map<String, Object> data;

  public InMemoryCaseContextStore() {
    this.data = new LinkedHashMap<>();
  }

  public InMemoryCaseContextStore(Map<String, Object> initial) {
    this.data = new LinkedHashMap<>(initial);
  }

  @Override
  public Object get(String key) {
    return data.get(key);
  }

  @Override
  public Object put(String key, Object value) {
    return data.put(key, value);
  }

  @Override
  public Object remove(String key) {
    return data.remove(key);
  }

  @Override
  public boolean containsKey(String key) {
    return data.containsKey(key);
  }

  @Override
  public Set<String> keySet() {
    return Set.copyOf(data.keySet());
  }

  @Override
  public Map<String, Object> snapshot() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }

  @Override
  public void clear() {
    data.clear();
  }

  @Override
  public void putAll(Map<String, Object> entries) {
    data.putAll(entries);
  }

  @Override
  public int size() {
    return data.size();
  }

  @Override
  public boolean isEmpty() {
    return data.isEmpty();
  }
}
