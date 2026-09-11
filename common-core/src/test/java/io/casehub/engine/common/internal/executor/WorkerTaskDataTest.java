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
package io.casehub.engine.common.internal.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerTaskDataTest {

  @Test
  void roundTripsViaMap() {
    UUID caseId = UUID.randomUUID();
    UUID signalId = UUID.randomUUID();
    var original =
        new WorkerTaskData(42L, "hash123", caseId, "worker1", "tenant1", "binding1", signalId);

    Map<String, String> map = original.toMap();
    WorkerTaskData restored = WorkerTaskData.fromMap(map);

    assertEquals(original, restored);
  }

  @Test
  void roundTripsWithNullOptionalFields() {
    UUID caseId = UUID.randomUUID();
    var original = new WorkerTaskData(1L, "hash", caseId, "w", "t", null, null);

    Map<String, String> map = original.toMap();
    WorkerTaskData restored = WorkerTaskData.fromMap(map);

    assertEquals(original, restored);
    assertNull(restored.bindingName());
    assertNull(restored.signalId());
  }

  @Test
  void withBindingNameReturnsNewInstance() {
    UUID caseId = UUID.randomUUID();
    var original = new WorkerTaskData(1L, "hash", caseId, "w", "t", null, null);

    var withBinding = original.withBindingName("b1");

    assertNull(original.bindingName());
    assertEquals("b1", withBinding.bindingName());
    assertEquals(original.eventLogId(), withBinding.eventLogId());
    assertEquals(original.caseId(), withBinding.caseId());
  }

  @Test
  void withSignalIdReturnsNewInstance() {
    UUID caseId = UUID.randomUUID();
    UUID signalId = UUID.randomUUID();
    var original = new WorkerTaskData(1L, "hash", caseId, "w", "t", null, null);

    var withSignal = original.withSignalId(signalId);

    assertNull(original.signalId());
    assertEquals(signalId, withSignal.signalId());
  }

  @Test
  void toMapOmitsNullOptionalFields() {
    UUID caseId = UUID.randomUUID();
    var data = new WorkerTaskData(1L, "hash", caseId, "w", "t", null, null);

    Map<String, String> map = data.toMap();

    assertFalse(map.containsKey("bindingName"));
    assertFalse(map.containsKey("signalId"));
    assertEquals(5, map.size());
  }
}
