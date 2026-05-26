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

import io.casehub.api.context.CaseContext;
import java.util.Map;
import java.util.Set;

/**
 * Migration shim for code written against the casehub-poc {@code CaseFile} API.
 *
 * <p>Bridges the naming gap between the poc's {@code put/get/keys} and the engine's {@code
 * set/getAs/getKeys}. Intended as a stepping-stone; once migration is complete call sites should
 * use {@link io.casehub.api.context.CaseContext} directly.
 *
 * <p><b>Null behaviour:</b> {@code put(key, null)} on an absent key is a no-op — the key is not
 * added. This differs from the poc's {@code HibernateCaseFile} which stored null entries directly.
 * Use {@code contains(key)} to test presence.
 */
public class MapCaseFile extends CaseContextImpl {

  public MapCaseFile() {}

  public MapCaseFile(final Map<String, Object> initial) {
    super(initial);
  }

  /**
   * poc-compatible alias for {@link #set(String, Object)}.
   *
   * <p>Note: unlike {@link #set}, this method returns {@code void} and does not support fluent
   * chaining.
   */
  public void put(final String key, final Object value) {
    set(key, value);
  }

  /**
   * poc-compatible alias for {@link #getAs(String, Class)}.
   *
   * <p>Returns {@code null} when the key is absent — not {@code Optional.empty()}. See platform
   * protocol PP-20260512-5f055d.
   */
  public <T> T get(final String key, final Class<T> type) {
    return getAs(key, type);
  }

  /** poc-compatible alias for {@link #getKeys()}. */
  public Set<String> keys() {
    return getKeys();
  }

  /**
   * Overrides {@link CaseContextImpl#snapshot()} to return a {@code MapCaseFile}, preserving the
   * poc-compatible {@code put/get/keys} aliases on the snapshot.
   */
  @Override
  public CaseContext snapshot() {
    final CaseContext base = super.snapshot();
    return new MapCaseFile(base.getData());
  }
}
