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
package io.casehub.engine.internal.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.common.spi.event.SelectionContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SelectionContextStoreTest {

  private final SelectionContextStore store = new SelectionContextStore();

  @Test
  void storeAndRemove() {
    UUID caseId = UUID.randomUUID();
    String worker = "agent-a";
    SelectionContext ctx =
        new SelectionContext(
            "composable",
            new SelectionContext.SelectedCandidate("agent-a", 0.9, "blended", "high"),
            List.of());

    store.store(caseId, worker, ctx);
    SelectionContext retrieved = store.remove(caseId, worker);

    assertThat(retrieved).isSameAs(ctx);
  }

  @Test
  void removeReturnsNullWhenAbsent() {
    assertThat(store.remove(UUID.randomUUID(), "no-such-worker")).isNull();
  }

  @Test
  void removeIsOneShot() {
    UUID caseId = UUID.randomUUID();
    String worker = "agent-b";
    store.store(
        caseId,
        worker,
        new SelectionContext(
            "composable",
            new SelectionContext.SelectedCandidate("agent-b", 0.5, "blended", null),
            List.of()));

    store.remove(caseId, worker);
    assertThat(store.remove(caseId, worker)).isNull();
  }
}
