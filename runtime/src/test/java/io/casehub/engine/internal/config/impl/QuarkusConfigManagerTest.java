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
package io.casehub.engine.internal.config.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.internal.config.ConfigManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class QuarkusConfigManagerTest {

  @Inject ConfigManager configManager;

  @Test
  void shouldResolveFromApplicationProperties() {
    Optional<Integer> timeout = configManager.config("test.timeout", Integer.class);
    assertThat(timeout).hasValue(5000);
  }

  @Test
  void shouldResolveFromSystemProperties() {
    System.setProperty("test.sysprop", "true");
    Optional<Boolean> flag = configManager.config("test.sysprop", Boolean.class);
    assertThat(flag).hasValue(true);
  }

  @Test
  void shouldHandleMultiValues() {
    Collection<String> items = configManager.multiConfig("test.items", String.class);
    assertThat(items).containsExactly("a", "b", "c");
  }

  @Test
  void shouldReturnEmptyForUnknownProperty() {
    Optional<String> unknown = configManager.config("unknown.property", String.class);
    assertThat(unknown).isEmpty();
  }

  @Test
  void shouldHandleTypeConversion() {
    assertThat(configManager.config("test.number", Integer.class)).hasValue(42);
    assertThat(configManager.config("test.number", String.class)).hasValue("42");
  }

  @Test
  void shouldListPropertyNames() {
    Iterable<String> names = configManager.names();
    assertThat(names).isNotEmpty();
  }
}
