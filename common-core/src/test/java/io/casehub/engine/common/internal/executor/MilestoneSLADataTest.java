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

class MilestoneSLADataTest {

  @Test
  void roundTripsViaMap() {
    UUID caseId = UUID.randomUUID();
    var original = new MilestoneSLAData(caseId, "orderDelivery");

    Map<String, String> map = original.toMap();
    MilestoneSLAData restored = MilestoneSLAData.fromMap(map);

    assertEquals(original, restored);
  }

  @Test
  void toMapContainsAllFields() {
    UUID caseId = UUID.randomUUID();
    var data = new MilestoneSLAData(caseId, "m1");

    Map<String, String> map = data.toMap();

    assertEquals(2, map.size());
    assertEquals(caseId.toString(), map.get("caseId"));
    assertEquals("m1", map.get("milestoneName"));
  }

  @Test
  void rejectsNullCaseId() {
    assertThrows(NullPointerException.class, () -> new MilestoneSLAData(null, "m"));
  }

  @Test
  void rejectsNullMilestoneName() {
    assertThrows(NullPointerException.class, () -> new MilestoneSLAData(UUID.randomUUID(), null));
  }
}
