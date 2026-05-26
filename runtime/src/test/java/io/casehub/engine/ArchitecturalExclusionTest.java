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
package io.casehub.engine;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Architectural fitness test — verifies that deprecated casehub-core types that are superseded by
 * casehub-engine equivalents are never introduced into the codebase.
 *
 * <p>Each excluded type has a documented casehub-engine replacement:
 *
 * <ul>
 *   <li>NotificationService → Vert.x EventBus ({@code eventBus.publish()} / {@code @ConsumeEvent})
 *   <li>CaseFileItemEvent → {@link CaseContextChangedEvent}
 *   <li>CaseFileContribution → {@link EventLog}
 *   <li>TaskLifecycleEvent → {@link CaseHubEventType} entries
 *   <li>AutonomousMonitoringWorker pattern → {@link io.casehub.api.engine.CaseHubRuntime#signal}
 * </ul>
 *
 * See casehubio/engine#118. Migration plan:
 * docs/superpowers/specs/2026-04-14-casehub-engine-migration-plan.md.
 */
class ArchitecturalExclusionTest {

  @Test
  void deprecated_casehub_core_types_are_not_on_the_classpath() {
    List<String> forbidden =
        List.of(
            "io.casehub.worker.NotificationService",
            "io.casehub.core.CaseFileItemEvent",
            "io.casehub.core.CaseFileContribution",
            "io.casehub.worker.TaskLifecycleEvent");

    for (String className : forbidden) {
      assertThatThrownBy(() -> Class.forName(className))
          .isInstanceOf(ClassNotFoundException.class)
          .as(
              "'%s' must not be present in casehub-engine — "
                  + "it is superseded by a casehub-engine equivalent. "
                  + "See casehubio/engine#118.",
              className);
    }
  }

  @Test
  void autonomous_work_notification_pattern_is_not_present() {
    // notifyAutonomousWork() is superseded by CaseHubRuntime.signal()
    assertThatThrownBy(
            () -> Class.forName("io.casehub.examples.workers.AutonomousMonitoringWorker"))
        .isInstanceOf(ClassNotFoundException.class)
        .as(
            "AutonomousMonitoringWorker must not be ported — "
                + "the autonomous work pattern is replaced by the Signal mechanism. "
                + "See casehubio/engine#118.");
  }
}
