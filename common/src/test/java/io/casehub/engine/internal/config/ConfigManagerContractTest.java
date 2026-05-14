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
package io.casehub.engine.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Abstract contract test for ConfigManager implementations.
 *
 * <p>Concrete implementations must extend this class and provide a ConfigManager instance via
 * {@link #configManager()}.
 */
public abstract class ConfigManagerContractTest {

  /**
   * Provide the ConfigManager implementation under test.
   *
   * <p>Implementations should ensure the following properties are available:
   *
   * <ul>
   *   <li>test.string=value
   *   <li>test.number=42
   *   <li>test.bool=true
   *   <li>test.multi=a,b,c
   * </ul>
   */
  protected abstract ConfigManager configManager();

  @Test
  void shouldReturnStringValue() {
    Optional<String> value = configManager().config("test.string", String.class);
    assertThat(value).isPresent().hasValue("value");
  }

  @Test
  void shouldReturnIntegerValue() {
    Optional<Integer> value = configManager().config("test.number", Integer.class);
    assertThat(value).isPresent().hasValue(42);
  }

  @Test
  void shouldReturnBooleanValue() {
    Optional<Boolean> value = configManager().config("test.bool", Boolean.class);
    assertThat(value).isPresent().hasValue(true);
  }

  @Test
  void shouldReturnEmptyForMissingProperty() {
    Optional<String> value = configManager().config("nonexistent", String.class);
    assertThat(value).isEmpty();
  }

  @Test
  void shouldReturnMultipleValues() {
    Collection<String> values = configManager().multiConfig("test.multi", String.class);
    assertThat(values).containsExactly("a", "b", "c");
  }

  @Test
  void shouldReturnEmptyCollectionForMissingMultiValue() {
    Collection<String> values = configManager().multiConfig("nonexistent", String.class);
    assertThat(values).isEmpty();
  }

  @Test
  void shouldListPropertyNames() {
    Iterable<String> names = configManager().names();
    assertThat(names).contains("test.string", "test.number", "test.bool", "test.multi");
  }
}
