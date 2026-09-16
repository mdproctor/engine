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
package io.casehub.engine.common.internal.observation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.spi.observation.ContextSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContextHistoryBufferTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private ContextHistoryBuffer buffer;
  private final UUID caseId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    buffer = new ContextHistoryBuffer();
  }

  @Test
  void computeChangedKeysDetectsAdditions() {
    ObjectNode snapshot = MAPPER.createObjectNode().put("a", 1).put("b", 2);
    Set<String> changed = buffer.computeChangedKeys(caseId, snapshot);
    assertEquals(Set.of("a", "b"), changed);
  }

  @Test
  void computeChangedKeysDetectsModifications() {
    ObjectNode first = MAPPER.createObjectNode().put("a", 1).put("b", 2);
    buffer.computeChangedKeys(caseId, first);

    ObjectNode second = MAPPER.createObjectNode().put("a", 1).put("b", 99);
    Set<String> changed = buffer.computeChangedKeys(caseId, second);
    assertEquals(Set.of("b"), changed);
  }

  @Test
  void computeChangedKeysDetectsRemovals() {
    ObjectNode first = MAPPER.createObjectNode().put("a", 1).put("b", 2);
    buffer.computeChangedKeys(caseId, first);

    ObjectNode second = MAPPER.createObjectNode().put("a", 1);
    Set<String> changed = buffer.computeChangedKeys(caseId, second);
    assertEquals(Set.of("b"), changed);
  }

  @Test
  void computeChangedKeysReturnsEmptyWhenNoChanges() {
    ObjectNode first = MAPPER.createObjectNode().put("a", 1).put("b", 2);
    buffer.computeChangedKeys(caseId, first);

    ObjectNode second = MAPPER.createObjectNode().put("a", 1).put("b", 2);
    Set<String> changed = buffer.computeChangedKeys(caseId, second);
    assertTrue(changed.isEmpty());
  }

  @Test
  void extractChangedValuesReturnsCorrectValues() {
    ObjectNode snapshot = MAPPER.createObjectNode().put("a", 1).put("b", 2);
    var values = buffer.extractChangedValues(snapshot, Set.of("a"));
    assertEquals(1, values.size());
    assertEquals(1, values.get("a").asInt());
  }

  @Test
  void extractChangedValuesSkipsRemovedKeys() {
    ObjectNode snapshot = MAPPER.createObjectNode().put("a", 1);
    var values = buffer.extractChangedValues(snapshot, Set.of("a", "removed"));
    assertEquals(1, values.size());
    assertNull(values.get("removed"));
  }

  @Test
  void historyBoundedByCount() {
    for (int i = 0; i < 5; i++) {
      buffer.record(caseId, new ContextSnapshot(Set.of("k"), Map.of(), Instant.now()));
    }
    var history = buffer.getHistory(caseId, 3, Duration.ofMinutes(5));
    assertEquals(3, history.size());
  }

  @Test
  void historyBoundedByAge() {
    Instant old = Instant.now().minus(Duration.ofMinutes(10));
    buffer.record(caseId, new ContextSnapshot(Set.of("k"), Map.of(), old));
    buffer.record(caseId, new ContextSnapshot(Set.of("k"), Map.of(), Instant.now()));

    var history = buffer.getHistory(caseId, 50, Duration.ofMinutes(5));
    assertEquals(1, history.size());
  }

  @Test
  void historyReturnsEmptyForUnknownCase() {
    assertTrue(buffer.getHistory(UUID.randomUUID(), 50, Duration.ofMinutes(5)).isEmpty());
  }

  @Test
  void evictRemovesAllState() {
    ObjectNode snap = MAPPER.createObjectNode().put("a", 1);
    buffer.computeChangedKeys(caseId, snap);
    buffer.record(caseId, new ContextSnapshot(Set.of("a"), Map.of(), Instant.now()));

    buffer.evict(caseId);

    assertTrue(buffer.getHistory(caseId, 50, Duration.ofMinutes(5)).isEmpty());
    ObjectNode snap2 = MAPPER.createObjectNode().put("a", 1);
    Set<String> changed = buffer.computeChangedKeys(caseId, snap2);
    assertEquals(Set.of("a"), changed);
  }

  @Test
  void evictExpiredRemovesOldEntries() {
    Instant old = Instant.now().minus(Duration.ofMinutes(10));
    buffer.record(caseId, new ContextSnapshot(Set.of("k"), Map.of(), old));
    buffer.record(caseId, new ContextSnapshot(Set.of("k"), Map.of(), Instant.now()));

    buffer.evictExpired(caseId, 50, Duration.ofMinutes(5));
    var history = buffer.getHistory(caseId, 50, Duration.ofMinutes(30));
    assertEquals(1, history.size());
  }

  @Test
  void evictExpiredRemovesExcessEntries() {
    for (int i = 0; i < 5; i++) {
      buffer.record(caseId, new ContextSnapshot(Set.of("k"), Map.of(), Instant.now()));
    }
    buffer.evictExpired(caseId, 3, Duration.ofMinutes(5));
    var history = buffer.getHistory(caseId, 50, Duration.ofMinutes(5));
    assertEquals(3, history.size());
  }

  @Test
  void resetClearsAll() {
    ObjectNode snap = MAPPER.createObjectNode().put("a", 1);
    buffer.computeChangedKeys(caseId, snap);
    buffer.record(caseId, new ContextSnapshot(Set.of("k"), Map.of(), Instant.now()));
    buffer.reset();
    assertTrue(buffer.getHistory(caseId, 50, Duration.ofMinutes(5)).isEmpty());
  }
}
