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
package io.casehub.engine.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.Goal;
import io.casehub.api.model.Milestone;
import io.casehub.api.view.BindingView;
import io.casehub.api.view.CaseDefinitionView;
import io.casehub.api.view.GoalView;
import io.casehub.api.view.MilestoneView;
import io.casehub.api.view.WorkerView;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseDefinitionServiceTest {

  private CaseDefinitionService service;
  private CaseMetaModel meta;

  @BeforeEach
  void setUp() {
    service =
        new CaseDefinitionService(
            null, // metaModelRepository — not used in mapDefinition
            null, // definitionRegistry
            null, // currentPrincipal
            null); // accessControlProvider
    meta = new CaseMetaModel();
    meta.setNamespace("test-ns");
    meta.setName("test-case");
    meta.setVersion("1.0");
  }

  @Test
  void mapDefinition_projectsWorkers() {
    var def =
        CaseDefinition.builder()
            .namespace("test-ns")
            .name("test-case")
            .version("1.0")
            .worker(
                Worker.builder()
                    .name("planner")
                    .capabilityNames("plan", "decompose")
                    .function(WorkerFunction.NONE)
                    .description("Plans work")
                    .definitionRef("agent:planner")
                    .build())
            .build();

    CaseDefinitionView view = service.mapDefinition(meta, def);

    assertEquals(1, view.workers().size());
    WorkerView w = view.workers().get(0);
    assertEquals("planner", w.name());
    assertTrue(w.capabilities().containsAll(List.of("plan", "decompose")));
    assertEquals("Plans work", w.description());
    assertEquals("agent:planner", w.definitionRef());
  }

  @Test
  void mapDefinition_projectsBindings() {
    var def =
        CaseDefinition.builder()
            .namespace("test-ns")
            .name("test-case")
            .version("1.0")
            .capability(Capability.of("analyze", ".", "."))
            .binding(
                Binding.builder()
                    .name("run-analysis")
                    .capability(Capability.of("analyze", ".", "."))
                    .on("$.data.ready")
                    .when(".status == \"pending\"")
                    .build())
            .build();

    CaseDefinitionView view = service.mapDefinition(meta, def);

    assertEquals(1, view.bindings().size());
    BindingView b = view.bindings().get(0);
    assertEquals("run-analysis", b.name());
    assertEquals("capability", b.targetType());
    assertEquals("analyze", b.targetName());
    assertEquals("contextChange", b.triggerType());
    assertEquals("$.data.ready", b.triggerExpression());
    assertEquals(".status == \"pending\"", b.when());
  }

  @Test
  void mapDefinition_projectsMilestones() {
    var def =
        CaseDefinition.builder()
            .namespace("test-ns")
            .name("test-case")
            .version("1.0")
            .milestone(
                Milestone.builder()
                    .name("data-collected")
                    .description("All data has been gathered")
                    .entryCriteria(".phase == \"collection\"")
                    .completionCriteria(".collected == true")
                    .slaDuration(Duration.ofHours(4))
                    .build())
            .build();

    CaseDefinitionView view = service.mapDefinition(meta, def);

    assertEquals(1, view.milestones().size());
    MilestoneView m = view.milestones().get(0);
    assertEquals("data-collected", m.name());
    assertEquals("All data has been gathered", m.description());
    assertEquals(".phase == \"collection\"", m.entryCriteria());
    assertEquals(".collected == true", m.completionCriteria());
    assertEquals("PT4H", m.slaDuration());
  }

  @Test
  void mapDefinition_projectsGoals() {
    var def =
        CaseDefinition.builder()
            .namespace("test-ns")
            .name("test-case")
            .version("1.0")
            .goal(
                Goal.builder()
                    .name("resolved")
                    .description("Case has been resolved")
                    .condition(".resolution != null")
                    .kind("SUCCESS")
                    .build())
            .build();

    CaseDefinitionView view = service.mapDefinition(meta, def);

    assertEquals(1, view.goals().size());
    GoalView g = view.goals().get(0);
    assertEquals("resolved", g.name());
    assertEquals("Case has been resolved", g.description());
    assertEquals(".resolution != null", g.condition());
    assertEquals("SUCCESS", g.kind());
  }

  @Test
  void mapDefinition_projectsPredicateCompletion() {
    var def =
        CaseDefinition.builder()
            .namespace("test-ns")
            .name("test-case")
            .version("1.0")
            .completion(".done == true")
            .build();

    CaseDefinitionView view = service.mapDefinition(meta, def);

    assertNotNull(view.completion());
    assertEquals("predicate", view.completion().type());
    assertEquals(".done == true", view.completion().condition());
    assertNull(view.completion().goals());
  }

  @Test
  void mapDefinition_nullDef_returnsNull() {
    assertNull(service.mapDefinition(meta, null));
  }

  @Test
  void mapDefinition_emptyLists_returnsEmptyViews() {
    var def =
        CaseDefinition.builder().namespace("test-ns").name("test-case").version("1.0").build();

    CaseDefinitionView view = service.mapDefinition(meta, def);

    assertNotNull(view);
    assertTrue(view.workers().isEmpty());
    assertTrue(view.bindings().isEmpty());
    assertTrue(view.milestones().isEmpty());
    assertTrue(view.goals().isEmpty());
    assertNull(view.completion());
  }
}
