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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetrievedMemoryTest {

  @Test
  void constructsWithValidFields() {
    var mem =
        new RetrievedMemory("id-1", "text", "experience", Instant.now(), Map.of("key", "val"));
    assertEquals("id-1", mem.memoryId());
    assertEquals("text", mem.text());
    assertEquals("experience", mem.domain());
    assertEquals("val", mem.attributes().get("key"));
  }

  @Test
  void rejectsNullMemoryId() {
    assertThrows(
        NullPointerException.class,
        () -> new RetrievedMemory(null, "text", "domain", Instant.now(), Map.of()));
  }

  @Test
  void rejectsNullText() {
    assertThrows(
        NullPointerException.class,
        () -> new RetrievedMemory("id", null, "domain", Instant.now(), Map.of()));
  }

  @Test
  void rejectsNullDomain() {
    assertThrows(
        NullPointerException.class,
        () -> new RetrievedMemory("id", "text", null, Instant.now(), Map.of()));
  }

  @Test
  void nullAttributesDefaultsToEmpty() {
    var mem = new RetrievedMemory("id", "text", "domain", Instant.now(), null);
    assertTrue(mem.attributes().isEmpty());
  }

  @Test
  void attributesAreImmutable() {
    var attrs = new java.util.HashMap<>(Map.of("k", "v"));
    var mem = new RetrievedMemory("id", "text", "domain", Instant.now(), attrs);
    assertThrows(UnsupportedOperationException.class, () -> mem.attributes().put("new", "val"));
  }
}
