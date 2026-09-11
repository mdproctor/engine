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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScheduledTriggerDataTest {

  @Test
  void roundTripsViaMap() {
    UUID caseId = UUID.randomUUID();
    var original = new ScheduledTriggerData(caseId, "binding1", "capability1", "worker1");

    Map<String, String> map = original.toMap();
    ScheduledTriggerData restored = ScheduledTriggerData.fromMap(map);

    assertEquals(original, restored);
  }

  @Test
  void toMapContainsAllFields() {
    UUID caseId = UUID.randomUUID();
    var data = new ScheduledTriggerData(caseId, "b", "c", "w");

    Map<String, String> map = data.toMap();

    assertEquals(4, map.size());
    assertEquals(caseId.toString(), map.get("caseId"));
    assertEquals("b", map.get("bindingName"));
    assertEquals("c", map.get("capabilityName"));
    assertEquals("w", map.get("workerName"));
  }

  @Test
  void rejectsNullCaseId() {
    assertThrows(NullPointerException.class, () -> new ScheduledTriggerData(null, "b", "c", "w"));
  }

  @Test
  void rejectsNullBindingName() {
    assertThrows(
        NullPointerException.class,
        () -> new ScheduledTriggerData(UUID.randomUUID(), null, "c", "w"));
  }

  @Test
  void rejectsNullCapabilityName() {
    assertThrows(
        NullPointerException.class,
        () -> new ScheduledTriggerData(UUID.randomUUID(), "b", null, "w"));
  }

  @Test
  void rejectsNullWorkerName() {
    assertThrows(
        NullPointerException.class,
        () -> new ScheduledTriggerData(UUID.randomUUID(), "b", "c", null));
  }
}
