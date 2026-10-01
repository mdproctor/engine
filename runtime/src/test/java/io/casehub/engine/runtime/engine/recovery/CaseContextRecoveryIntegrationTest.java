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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.context.CaseContext;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.recovery.CaseContextRecoveryStrategy;
import io.casehub.engine.persistence.memory.InMemoryEventLogRepository;
import io.casehub.engine.runtime.context.CaseContextImpl;
import jakarta.enterprise.inject.Typed;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaseContextRecoveryIntegrationTest {

  @Test
  void snapshotStrategyHasDefaultBeanAnnotation() {
    assertTrue(
        SnapshotRecoveryStrategy.class.isAnnotationPresent(io.quarkus.arc.DefaultBean.class),
        "SnapshotRecoveryStrategy must be @DefaultBean");
  }

  @Test
  void eventLogStrategyHasTypedAnnotation() {
    assertTrue(
        EventLogReplayRecoveryStrategy.class.isAnnotationPresent(Typed.class),
        "EventLogReplayRecoveryStrategy must be @Typed");
    var annotation = EventLogReplayRecoveryStrategy.class.getAnnotation(Typed.class);
    assertEquals(1, annotation.value().length);
    assertEquals(EventLogReplayRecoveryStrategy.class, annotation.value()[0]);
  }

  @Test
  void bothStrategiesImplementSPI() {
    assertTrue(CaseContextRecoveryStrategy.class.isAssignableFrom(SnapshotRecoveryStrategy.class));
    assertTrue(
        CaseContextRecoveryStrategy.class.isAssignableFrom(EventLogReplayRecoveryStrategy.class));
  }

  @Test
  void snapshotRoundTripEndToEnd() {
    CaseContextImpl context = new CaseContextImpl();
    context.set("integration", "test");
    context.set("count", 7);
    context.writableLayer("semantic").set("fact", "verified");

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());

    EventLogReplayRecoveryStrategy fallback =
        new EventLogReplayRecoveryStrategy(new InMemoryEventLogRepository());
    SnapshotRecoveryStrategy snapshot = new SnapshotRecoveryStrategy(fallback);
    snapshot.onContextChanged(instance, context);

    CaseInstance loaded = new CaseInstance();
    loaded.setUuid(instance.getUuid());
    loaded.setContextSnapshot(instance.getContextSnapshot());

    CaseContext recovered = snapshot.recover(loaded);
    assertEquals("test", recovered.getString("integration"));
    assertEquals(7, recovered.getInt("count"));
    assertEquals("verified", recovered.layer("semantic").get("fact"));
  }
}
