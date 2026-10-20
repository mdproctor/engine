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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.Milestone;
import io.casehub.api.view.CoordinationPipelineView;
import io.casehub.api.view.PhaseItemView;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import org.junit.jupiter.api.Test;

class CoordinationPipelineProjectionTest {

  private final CoordinationPipelineProjection projection = new CoordinationPipelineProjection();

  @Test
  void project_scopeActivatedBindings_producesParallelPhase() {
    var def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .capability(Capability.of("a", ".", "."))
            .capability(Capability.of("b", ".", "."))
            .worker(
                Worker.builder()
                    .name("w1")
                    .capabilityName("a")
                    .function(WorkerFunction.NONE)
                    .build())
            .worker(
                Worker.builder()
                    .name("w2")
                    .capabilityName("b")
                    .function(WorkerFunction.NONE)
                    .build())
            .binding(
                Binding.builder()
                    .name("do-a")
                    .capability(Capability.of("a", ".", "."))
                    .on(new io.casehub.api.model.ScopeActivatedTrigger())
                    .lifecycleScope(io.casehub.api.model.LifecycleScope.CASE)
                    .participation(io.casehub.api.model.Participation.COMPANION)
                    .build())
            .binding(
                Binding.builder()
                    .name("do-b")
                    .capability(Capability.of("b", ".", "."))
                    .on(new io.casehub.api.model.ScopeActivatedTrigger())
                    .lifecycleScope(io.casehub.api.model.LifecycleScope.CASE)
                    .participation(io.casehub.api.model.Participation.COMPANION)
                    .build())
            .build();

    CoordinationPipelineView pipeline = projection.project(def);

    assertEquals("test", pipeline.name());
    assertEquals(2, pipeline.agentCount());
    assertFalse(pipeline.phases().isEmpty());
    PhaseItemView parallel = pipeline.phases().get(0);
    assertEquals("parallel", parallel.type());
    assertNotNull(parallel.lanes());
    assertEquals(2, parallel.lanes().size());
  }

  @Test
  void project_singleScopeActivated_producesStepNotParallel() {
    var def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .capability(Capability.of("a", ".", "."))
            .binding(
                Binding.builder()
                    .name("do-a")
                    .capability(Capability.of("a", ".", "."))
                    .on(new io.casehub.api.model.ScopeActivatedTrigger())
                    .lifecycleScope(io.casehub.api.model.LifecycleScope.CASE)
                    .participation(io.casehub.api.model.Participation.COMPANION)
                    .build())
            .build();

    CoordinationPipelineView pipeline = projection.project(def);

    assertEquals(1, pipeline.phases().size());
    assertEquals("step", pipeline.phases().get(0).type());
    assertEquals("invoke", pipeline.phases().get(0).action());
  }

  @Test
  void project_milestonesAppearAsSignals() {
    var def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .milestone(
                Milestone.builder()
                    .name("checkpoint")
                    .description("A barrier")
                    .entryCriteria(".ready")
                    .completionCriteria(".done")
                    .build())
            .build();

    CoordinationPipelineView pipeline = projection.project(def);

    assertEquals(1, pipeline.barrierCount());
    assertEquals(1, pipeline.phases().size());
    PhaseItemView signal = pipeline.phases().get(0);
    assertEquals("signal", signal.type());
    assertEquals("checkpoint", signal.name());
    assertEquals("A barrier", signal.description());
  }

  @Test
  void project_contextTriggeredBindings_produceSteps() {
    var def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .capability(Capability.of("analyze", ".", "."))
            .binding(
                Binding.builder()
                    .name("run")
                    .capability(Capability.of("analyze", ".", "."))
                    .on("$.ready")
                    .build())
            .build();

    CoordinationPipelineView pipeline = projection.project(def);

    assertEquals(1, pipeline.phases().size());
    PhaseItemView step = pipeline.phases().get(0);
    assertEquals("step", step.type());
    assertEquals("invoke", step.action());
    assertEquals("analyze", step.target());
  }

  @Test
  void project_emptyDefinition_producesEmptyPipeline() {
    var def = CaseDefinition.builder().namespace("ns").name("empty").version("1.0").build();

    CoordinationPipelineView pipeline = projection.project(def);

    assertEquals("empty", pipeline.name());
    assertEquals(0, pipeline.agentCount());
    assertEquals(0, pipeline.barrierCount());
    assertEquals(0, pipeline.channelCount());
    assertTrue(pipeline.phases().isEmpty());
  }
}
