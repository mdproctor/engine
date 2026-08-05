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
package io.casehub.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MemoryRetrievalConfigTest {

  @Test
  void defaults_returnsExpectedValues() {
    var config = MemoryRetrievalConfig.defaults();
    assertFalse(config.enabled());
    assertEquals(10, config.maxMemories());
    assertEquals(Set.of("experience", "reflection"), config.domains());
  }

  @Test
  void rejectsZeroMaxMemories() {
    assertThrows(
        IllegalArgumentException.class, () -> new MemoryRetrievalConfig(true, 0, Set.of()));
  }

  @Test
  void domainsAreImmutable() {
    var domains = new java.util.HashSet<>(Set.of("experience"));
    var config = new MemoryRetrievalConfig(true, 10, domains);
    assertThrows(UnsupportedOperationException.class, () -> config.domains().add("new"));
  }

  @Test
  void nullDomainsDefaultsToEmpty() {
    var config = new MemoryRetrievalConfig(true, 10, null);
    assertTrue(config.domains().isEmpty());
  }
}
