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
package io.casehub.examples.typedcontext;

import io.casehub.api.context.CaseContextStore;
import io.casehub.api.context.ContextChangeEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A CaseContextStore that wraps InMemoryCaseContextStore and records every write to an audit log.
 * Demonstrates the store SPI without external dependencies.
 */
public class AuditingCaseContextStore implements CaseContextStore {

  private final Map<String, Object> data = new LinkedHashMap<>();
  private final List<ContextChangeEvent> auditLog = new CopyOnWriteArrayList<>();

  @Override
  public Object get(String key) {
    return data.get(key);
  }

  @Override
  public Object put(String key, Object value) {
    Object prev = data.put(key, value);
    auditLog.add(new ContextChangeEvent(key, prev, value));
    return prev;
  }

  @Override
  public Object remove(String key) {
    Object prev = data.remove(key);
    if (prev != null) {
      auditLog.add(new ContextChangeEvent(key, prev, null));
    }
    return prev;
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
    entries.forEach(this::put);
  }

  @Override
  public int size() {
    return data.size();
  }

  @Override
  public boolean isEmpty() {
    return data.isEmpty();
  }

  public List<ContextChangeEvent> getAuditLog() {
    return Collections.unmodifiableList(auditLog);
  }
}
