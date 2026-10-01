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
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChoreographyLoopControlTest {

  private final ChoreographyLoopControl loopControl = new ChoreographyLoopControl();

  private PlanExecutionContext ctx(CaseStatus status) {
    return new PlanExecutionContext(
        UUID.randomUUID(),
        mock(CaseDefinition.class),
        mock(CaseContext.class),
        status,
        null,
        List.of(),
        null,
        null);
  }

  private Binding binding(String name) {
    Binding b = mock(Binding.class);
    org.mockito.Mockito.when(b.getName()).thenReturn(name);
    return b;
  }

  @Test
  void runningCase_returnsAllEligibleBindings() {
    List<Binding> eligible = List.of(binding("a"), binding("b"));

    List<Binding> selected = loopControl.select(ctx(CaseStatus.RUNNING), eligible);

    assertThat(selected).containsExactlyElementsOf(eligible);
  }

  @Test
  void waitingCase_returnsEmptyList() {
    List<Binding> eligible = List.of(binding("a"), binding("b"));

    List<Binding> selected = loopControl.select(ctx(CaseStatus.WAITING), eligible);

    assertThat(selected).isEmpty();
  }

  @Test
  void suspendedCase_returnsEmptyList() {
    List<Binding> eligible = List.of(binding("a"));

    List<Binding> selected = loopControl.select(ctx(CaseStatus.SUSPENDED), eligible);

    assertThat(selected).isEmpty();
  }

  @Test
  void completedCase_returnsEmptyList() {
    List<Binding> eligible = List.of(binding("a"));

    List<Binding> selected = loopControl.select(ctx(CaseStatus.COMPLETED), eligible);

    assertThat(selected).isEmpty();
  }

  @Test
  void faultedCase_returnsEmptyList() {
    List<Binding> eligible = List.of(binding("a"));

    List<Binding> selected = loopControl.select(ctx(CaseStatus.FAULTED), eligible);

    assertThat(selected).isEmpty();
  }

  @Test
  void runningCase_emptyEligible_returnsEmpty() {
    List<Binding> selected = loopControl.select(ctx(CaseStatus.RUNNING), List.of());

    assertThat(selected).isEmpty();
  }
}
