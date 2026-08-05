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

import io.casehub.api.context.PropagationContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerContextTest {

  @Test
  void happyPath_allFieldsAccessible() {
    var caseId = UUID.randomUUID();
    var channel = new CaseChannel("ch-1", "n", "p", "qhorus", Map.of());
    var ctx = PropagationContext.createRoot();
    var wc =
        new WorkerContext(
            "refactor auth",
            caseId,
            List.of(channel),
            List.of(),
            ctx,
            Map.of("hint", "focus on token refresh"));
    assertThat(wc.taskDescription()).isEqualTo("refactor auth");
    assertThat(wc.caseId()).isEqualTo(caseId);
    assertThat(wc.channels()).containsExactly(channel);
    assertThat(wc.priorWorkers()).isEmpty();
    assertThat(wc.propagationContext()).isEqualTo(ctx);
    assertThat(wc.properties()).containsEntry("hint", "focus on token refresh");
  }

  @Test
  void nullPriorWorkers_defaultsToEmptyList() {
    var wc =
        new WorkerContext(
            "task", UUID.randomUUID(), null, null, PropagationContext.createRoot(), null);
    assertThat(wc.priorWorkers()).isNotNull().isEmpty();
    assertThat(wc.properties()).isNotNull().isEmpty();
  }

  @Test
  void priorWorkers_areImmutable() {
    var mutable = new ArrayList<WorkerSummary>();
    var wc =
        new WorkerContext(
            "task", UUID.randomUUID(), null, mutable, PropagationContext.createRoot(), null);
    assertThatThrownBy(() -> wc.priorWorkers().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void properties_areImmutable() {
    var mutable = new HashMap<String, Object>();
    mutable.put("k", "v");
    var wc =
        new WorkerContext(
            "task", UUID.randomUUID(), null, null, PropagationContext.createRoot(), mutable);
    mutable.put("extra", "injected");
    assertThat(wc.properties()).doesNotContainKey("extra");
  }

  @Test
  void eightArgConstructor_memoriesImmutable() {
    var mem = new RetrievedMemory("id", "text", "exp", java.time.Instant.now(), Map.of());
    var ctx =
        new WorkerContext(
            "desc",
            UUID.randomUUID(),
            List.of(),
            List.of(),
            null,
            Map.of(),
            List.of(),
            List.of(mem));
    assertThat(ctx.memories()).hasSize(1);
    assertThatThrownBy(() -> ctx.memories().add(mem))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void sevenArgConstructor_memoriesEmpty() {
    var ctx =
        new WorkerContext(
            "desc", UUID.randomUUID(), List.of(), List.of(), null, Map.of(), List.of());
    assertThat(ctx.memories()).isEmpty();
  }

  @Test
  void nullMemories_defaultsToEmptyList() {
    var ctx =
        new WorkerContext(
            "desc", UUID.randomUUID(), List.of(), List.of(), null, Map.of(), List.of(), null);
    assertThat(ctx.memories()).isNotNull().isEmpty();
  }
}
