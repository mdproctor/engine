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
package io.casehub.api.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test verifying that the default {@code onChange()} and {@code onAnyChange()} methods on
 * {@link CaseContext} compile and return no-op subscriptions, per the SPI evolution protocol.
 */
@DisplayName("CaseContext default method contract")
class CaseContextDefaultMethodContractTest {

  /** Minimal anonymous implementation — only abstract methods stubbed. */
  private final CaseContext minimalImpl =
      new CaseContext() {
        @Override
        public ReadableLayer layer(String name) {
          return null;
        }

        @Override
        public Map<String, Object> getData() {
          return Map.of();
        }

        @Override
        public CaseContext set(String key, Object value) {
          return this;
        }

        @Override
        public Object get(String key) {
          return null;
        }

        @Override
        public <T> T getAs(String key, Class<T> type) {
          return null;
        }

        @Override
        public <T> T getOrDefault(String key, T defaultValue) {
          return defaultValue;
        }

        @Override
        public Object computeIfAbsent(String key, Function<String, Object> mappingFunction) {
          return null;
        }

        @Override
        public Object putIfAbsent(String key, Object value) {
          return null;
        }

        @Override
        public boolean compareAndSet(String key, Object expected, Object newValue) {
          return false;
        }

        @Override
        public CaseContext update(String key, Function<Object, Object> updateFunction) {
          return this;
        }

        @Override
        public String getString(String key) {
          return null;
        }

        @Override
        public Integer getInt(String key) {
          return null;
        }

        @Override
        public Long getLong(String key) {
          return null;
        }

        @Override
        public Double getDouble(String key) {
          return null;
        }

        @Override
        public Boolean getBoolean(String key) {
          return null;
        }

        @Override
        public <T> List<T> getList(String key, Class<T> elementType) {
          return null;
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
        public CaseContext setPath(String path, Object value) {
          return this;
        }

        @Override
        public Optional<JsonNode> applyAndDiff(String path, Object value) {
          return Optional.empty();
        }

        @Override
        public CaseContext setAll(Map<String, Object> values) {
          return this;
        }

        @Override
        public Map<String, Object> getAll(String... keys) {
          return Map.of();
        }

        @Override
        public boolean contains(String key) {
          return false;
        }

        @Override
        public CaseContext remove(String key) {
          return this;
        }

        @Override
        public CaseContext clear() {
          return this;
        }

        @Override
        public Set<String> getKeys() {
          return Set.of();
        }

        @Override
        public boolean isEmpty() {
          return true;
        }

        @Override
        public int size() {
          return 0;
        }

        @Override
        public JsonNode asJsonNode() {
          return null;
        }

        @Override
        public CaseContext merge(CaseContext other) {
          return this;
        }

        @Override
        public CaseContext snapshot() {
          return this;
        }

        @Override
        public JsonNode diff(CaseContext other) {
          return null;
        }

        @Override
        public void applyDiff(JsonNode diff) {}

        @Override
        public long getVersion() {
          return 0;
        }
      };

  @Test
  @DisplayName("onChange() default returns NOOP subscription")
  void onChange_returnsNoopSubscription() {
    Subscription sub = minimalImpl.onChange("key", event -> {});
    assertThat(sub).isSameAs(Subscription.NOOP);
    // cancel() is a no-op and must not throw
    sub.cancel();
  }

  @Test
  @DisplayName("onAnyChange() default returns NOOP subscription")
  void onAnyChange_returnsNoopSubscription() {
    Subscription sub = minimalImpl.onAnyChange(event -> {});
    assertThat(sub).isSameAs(Subscription.NOOP);
    sub.cancel();
  }
}
