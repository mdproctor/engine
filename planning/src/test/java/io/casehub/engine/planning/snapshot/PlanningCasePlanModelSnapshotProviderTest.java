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
package io.casehub.engine.planning.snapshot;

import static org.assertj.core.api.Assertions.*;

import io.casehub.api.model.ExecutorRef;
import io.casehub.engine.plan.snapshot.CompoundItemSnapshot;
import io.casehub.engine.plan.snapshot.PrimitiveItemSnapshot;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanningCasePlanModelSnapshotProviderTest {

  private BlackboardRegistry registry;
  private PlanningCasePlanModelSnapshotProvider provider;

  @BeforeEach
  void setUp() {
    registry = new BlackboardRegistry();
    provider = new PlanningCasePlanModelSnapshotProvider(registry);
  }

  @Test
  void returnsEmptyWhenNoPlanModel() {
    assertThat(provider.getSnapshot(UUID.randomUUID(), "t")).isEmpty();
  }

  @Test
  void returnsSnapshotWithAgendaItems() {
    UUID caseId = UUID.randomUUID();
    CasePlanModel plan = registry.getOrCreate(caseId, "tenant-1");
    plan.addPlanItem(PlanItem.create("binding-a", ExecutorRef.of("worker-1", null), 10));

    var snapshot = provider.getSnapshot(caseId, "tenant-1");

    assertThat(snapshot).isPresent();
    assertThat(snapshot.get().caseId()).isEqualTo(caseId);
    assertThat(snapshot.get().agenda()).hasSize(1);
    assertThat(snapshot.get().agenda().get(0).bindingName()).isEqualTo("binding-a");
    assertThat(snapshot.get().agenda().get(0).status()).isEqualTo("PENDING");
  }

  @Test
  void returnsSnapshotWithFocusAndBudget() {
    UUID caseId = UUID.randomUUID();
    CasePlanModel plan = registry.getOrCreate(caseId, "tenant-1");
    plan.setFocus("entity-resolution");
    plan.setFocusRationale("highest priority");
    plan.setResourceBudget(java.util.Map.of("maxWorkers", 5));

    var snapshot = provider.getSnapshot(caseId, "tenant-1");

    assertThat(snapshot).isPresent();
    assertThat(snapshot.get().focus()).isEqualTo("entity-resolution");
    assertThat(snapshot.get().focusRationale()).isEqualTo("highest priority");
    assertThat(snapshot.get().resourceBudget()).containsEntry("maxWorkers", 5);
  }

  @Test
  void returnsDefinitionsForCompound() {
    UUID caseId = UUID.randomUUID();
    CasePlanModel plan = registry.getOrCreate(caseId, "tenant-1");

    var compound =
        PlanItemDefinition.Compound.builder("phase-1")
            .id("compound-1")
            .child(
                new PlanItemDefinition.Primitive(
                    "prim-1", "step-a", ExecutorRef.of("exec-1", "analysis executor"), null))
            .build();
    plan.registerDefinition(compound);

    var defs = provider.getDefinitions(caseId, "tenant-1");

    assertThat(defs).hasSize(1);
    assertThat(defs.get(0)).isInstanceOf(CompoundItemSnapshot.class);
    var cs = (CompoundItemSnapshot) defs.get(0);
    assertThat(cs.id()).isEqualTo("compound-1");
    assertThat(cs.name()).isEqualTo("phase-1");
    assertThat(cs.children()).hasSize(1);
    assertThat(cs.children().get(0)).isInstanceOf(PrimitiveItemSnapshot.class);
    var ps = (PrimitiveItemSnapshot) cs.children().get(0);
    assertThat(ps.executorName()).isEqualTo("exec-1");
    assertThat(ps.executorDescription()).isEqualTo("analysis executor");
  }

  @Test
  void returnsEmptyDefinitionsWhenNoPlan() {
    assertThat(provider.getDefinitions(UUID.randomUUID(), "t")).isEmpty();
  }
}
