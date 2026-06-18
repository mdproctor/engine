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

import io.casehub.api.context.PropagationContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProvisionContextTest {

  @Test
  void happyPath_allFieldsAccessible() {
    var caseId = UUID.randomUUID();
    var wc =
        new WorkerContext(
            "task", caseId, null, List.of(), PropagationContext.createRoot(), Map.of());
    var prop = PropagationContext.createRoot();
    var pc = new ProvisionContext(caseId, "tenant-1", "code-reviewer", wc, prop, null, null);
    assertThat(pc.caseId()).isEqualTo(caseId);
    assertThat(pc.tenancyId()).isEqualTo("tenant-1");
    assertThat(pc.taskType()).isEqualTo("code-reviewer");
    assertThat(pc.workerContext()).isEqualTo(wc);
    assertThat(pc.propagationContext()).isEqualTo(prop);
    assertThat(pc.triggerChannelId()).isNull();
    assertThat(pc.triggerCorrelationId()).isNull();
  }

  @Test
  void nullWorkerContext_isPermitted() {
    var pc =
        new ProvisionContext(
            UUID.randomUUID(),
            "tenant-1",
            "task-type",
            null,
            PropagationContext.createRoot(),
            null,
            null);
    assertThat(pc.workerContext()).isNull();
  }

  @Test
  void triggerFields_arePopulatedWhenProvided() {
    var pc =
        new ProvisionContext(
            UUID.randomUUID(),
            "tenant-1",
            "task-type",
            null,
            PropagationContext.createRoot(),
            "ch-abc-123",
            "corr-xyz-456");
    assertThat(pc.triggerChannelId()).isEqualTo("ch-abc-123");
    assertThat(pc.triggerCorrelationId()).isEqualTo("corr-xyz-456");
  }
}
