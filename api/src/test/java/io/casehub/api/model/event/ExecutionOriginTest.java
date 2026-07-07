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
package io.casehub.api.model.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.spi.routing.RetrievedExperience;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ExecutionOriginTest {

  @Test
  void enumValues_cover_all_execution_paths() {
    assertThat(ExecutionOrigin.values())
        .containsExactlyInAnyOrder(
            ExecutionOrigin.BINDING_DISPATCH,
            ExecutionOrigin.SIGNAL,
            ExecutionOrigin.SCHEDULE_TRIGGER,
            ExecutionOrigin.SUBCASE_COMPLETION,
            ExecutionOrigin.RECOVERY);
  }

  @Test
  void planExecutionContext_accepts_origin() {
    UUID caseId = UUID.randomUUID();
    CaseDefinition def = Mockito.mock(CaseDefinition.class);
    CaseContext ctx = Mockito.mock(CaseContext.class);
    List<RetrievedExperience> experiences = List.of();

    PlanExecutionContext planCtx =
        new PlanExecutionContext(
            caseId,
            def,
            ctx,
            CaseStatus.RUNNING,
            "tenant-1",
            experiences,
            ExecutionOrigin.SIGNAL,
            null);

    assertThat(planCtx.origin()).isEqualTo(ExecutionOrigin.SIGNAL);
  }

  @Test
  void planExecutionContext_accepts_null_origin() {
    UUID caseId = UUID.randomUUID();
    CaseDefinition def = Mockito.mock(CaseDefinition.class);
    CaseContext ctx = Mockito.mock(CaseContext.class);
    List<RetrievedExperience> experiences = List.of();

    PlanExecutionContext planCtx =
        new PlanExecutionContext(
            caseId, def, ctx, CaseStatus.RUNNING, "tenant-1", experiences, null, null);

    assertThat(planCtx.origin()).isNull();
  }
}
