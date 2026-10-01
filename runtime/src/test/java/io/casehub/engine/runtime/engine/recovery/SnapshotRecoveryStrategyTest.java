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
package io.casehub.engine.runtime.engine.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import io.casehub.api.context.CaseContext;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.persistence.memory.InMemoryEventLogRepository;
import io.casehub.engine.runtime.context.CaseContextImpl;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SnapshotRecoveryStrategyTest {

  private SnapshotRecoveryStrategy strategy;

  @BeforeEach
  void setUp() {
    EventLogReplayRecoveryStrategy fallback =
        new EventLogReplayRecoveryStrategy(new InMemoryEventLogRepository());
    strategy = new SnapshotRecoveryStrategy(fallback);
  }

  @Test
  void onContextChangedSetsSnapshotOnInstance() {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    CaseContextImpl context = new CaseContextImpl();
    context.set("status", "active");
    context.set("score", 42);

    strategy.onContextChanged(instance, context);

    assertNotNull(instance.getContextSnapshot());
    assertTrue(instance.getContextSnapshot().has("working"));
  }

  @Test
  void recoverDeserializesSnapshotToContext() {
    CaseContextImpl original = new CaseContextImpl();
    original.set("status", "active");
    original.set("score", 42);
    JsonNode snapshot = original.asJsonNode();

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setContextSnapshot(snapshot);

    CaseContext recovered = strategy.recover(instance);

    assertEquals("active", recovered.getString("status"));
    assertEquals(42, recovered.getInt("score"));
  }

  @Test
  void recoverWithNullSnapshotDelegatesToFallback() {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());

    CaseContext recovered = strategy.recover(instance);

    assertNotNull(recovered);
  }

  @Test
  void recoverWithNullNodeSnapshotDelegatesToFallback() {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setContextSnapshot(NullNode.getInstance());

    CaseContext recovered = strategy.recover(instance);

    assertNotNull(recovered);
  }

  @Test
  void roundTripPreservesAllLayers() {
    CaseContextImpl original = new CaseContextImpl();
    original.set("key", "value");
    original.writableLayer("semantic").set("fact", "observed");
    original.writableLayer("episodic").set("event", "happened");

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());

    strategy.onContextChanged(instance, original);

    CaseInstance restored = new CaseInstance();
    restored.setUuid(instance.getUuid());
    restored.setContextSnapshot(instance.getContextSnapshot());

    CaseContext recovered = strategy.recover(restored);
    assertEquals("value", recovered.getString("key"));
    assertEquals("observed", recovered.layer("semantic").get("fact"));
    assertEquals("happened", recovered.layer("episodic").get("event"));
  }
}
