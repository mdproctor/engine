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
package io.casehub.api.spi.observation;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.model.TaskStatus;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NeighborTest {

  @Test
  void construction() {
    var neighbor =
        new Neighbor(
            "agent-1",
            Set.of("analysis", "search"),
            TaskStatus.RUNNING,
            "binding-a",
            Set.of(NeighborRelation.COACTIVE));
    assertEquals("agent-1", neighbor.agentId());
    assertEquals(Set.of("analysis", "search"), neighbor.capabilities());
    assertEquals(TaskStatus.RUNNING, neighbor.currentStatus());
    assertEquals("binding-a", neighbor.bindingName());
    assertEquals(Set.of(NeighborRelation.COACTIVE), neighbor.relations());
  }

  @Test
  void multipleRelations() {
    var neighbor =
        new Neighbor(
            "agent-2",
            Set.of("review"),
            TaskStatus.RUNNING,
            "binding-b",
            Set.of(NeighborRelation.COACTIVE, NeighborRelation.SHARED_INTEREST));
    assertEquals(2, neighbor.relations().size());
    assertTrue(neighbor.relations().contains(NeighborRelation.COACTIVE));
    assertTrue(neighbor.relations().contains(NeighborRelation.SHARED_INTEREST));
  }

  @Test
  void allRelationValues() {
    assertEquals(4, NeighborRelation.values().length);
    assertNotNull(NeighborRelation.valueOf("COACTIVE"));
    assertNotNull(NeighborRelation.valueOf("SHARED_INTEREST"));
    assertNotNull(NeighborRelation.valueOf("SHARED_SIGNAL"));
    assertNotNull(NeighborRelation.valueOf("COMPLEMENTARY"));
  }
}
