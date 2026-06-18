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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConflictResolverTest {

  @Test
  void nullStrategy_lastWriterWins() {
    assertThat(ConflictResolver.resolve(null, "k", "old", "new")).isEqualTo("new");
  }

  @Test
  void lastWriterWins_returnsIncoming() {
    assertThat(ConflictResolver.resolve("LAST_WRITER_WINS", "k", "old", "new")).isEqualTo("new");
  }

  @Test
  void firstWriterWins_returnsExisting() {
    assertThat(ConflictResolver.resolve("FIRST_WRITER_WINS", "k", "old", "new")).isEqualTo("old");
  }

  @Test
  void fail_throwsOnConflict() {
    assertThatThrownBy(() -> ConflictResolver.resolve("FAIL", "k", "old", "new"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("FAIL");
  }

  @Test
  void nullExisting_returnsIncoming() {
    assertThat(ConflictResolver.resolve("FIRST_WRITER_WINS", "k", null, "new")).isEqualTo("new");
  }

  @Test
  @SuppressWarnings("unchecked")
  void deepMerge_mergesMaps() {
    Map<String, Object> existing =
        new LinkedHashMap<>(Map.of("status", "FAILED", "attempts", 3, "excludedAgents", "agent-1"));
    Map<String, Object> incoming = new LinkedHashMap<>(Map.of("result", "success", "status", "OK"));

    Object merged = ConflictResolver.resolve("DEEP_MERGE", "k", existing, incoming);

    assertThat(merged).isInstanceOf(Map.class);
    Map<String, Object> mergedMap = (Map<String, Object>) merged;
    assertThat(mergedMap).containsEntry("result", "success");
    assertThat(mergedMap).containsEntry("status", "OK");
    assertThat(mergedMap).containsEntry("attempts", 3);
    assertThat(mergedMap).containsEntry("excludedAgents", "agent-1");
  }

  @Test
  @SuppressWarnings("unchecked")
  void deepMerge_recursivelyMergesNestedMaps() {
    Map<String, Object> nested = new LinkedHashMap<>(Map.of("a", 1, "b", 2));
    Map<String, Object> existing = new LinkedHashMap<>(Map.of("inner", nested));
    Map<String, Object> incoming = new LinkedHashMap<>(Map.of("inner", Map.of("b", 99, "c", 3)));

    Object merged = ConflictResolver.resolve("DEEP_MERGE", "k", existing, incoming);

    assertThat(merged).isInstanceOf(Map.class);
    Map<String, Object> inner = (Map<String, Object>) ((Map<String, Object>) merged).get("inner");
    assertThat(inner).containsEntry("a", 1);
    assertThat(inner).containsEntry("b", 99);
    assertThat(inner).containsEntry("c", 3);
  }

  @Test
  void deepMerge_nonMapExisting_returnsIncoming() {
    assertThat(ConflictResolver.resolve("DEEP_MERGE", "k", "scalar", Map.of("a", 1)))
        .isEqualTo(Map.of("a", 1));
  }

  @Test
  void deepMerge_nonMapIncoming_returnsIncoming() {
    assertThat(ConflictResolver.resolve("DEEP_MERGE", "k", Map.of("a", 1), "scalar"))
        .isEqualTo("scalar");
  }

  @Test
  void unknownStrategy_fallsBackToLastWriterWins() {
    assertThat(ConflictResolver.resolve("UNKNOWN", "k", "old", "new")).isEqualTo("new");
  }
}
