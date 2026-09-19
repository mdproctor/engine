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
package io.casehub.engine.planning.control;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.stigmergy.StigmergyConfig;
import io.casehub.engine.common.internal.convergence.ActivityTracker;
import io.casehub.engine.common.internal.observation.ObservationRegistry;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import io.casehub.engine.internal.stigmergy.StigmergyCoordinator;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.worker.api.Capability;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StigmergyStrategyTest {

  private StigmergyStrategy strategy;
  private StigmergyCoordinator coordinator;

  @BeforeEach
  void setUp() {
    coordinator =
        new StigmergyCoordinator(
            new SignalRegistry(), new ObservationRegistry(), new ActivityTracker());
    strategy = new StigmergyStrategy(coordinator);
  }

  @Test
  void idIsStigmergy() {
    assertEquals("stigmergy", strategy.id());
  }

  @Test
  void firstSelectReturnsAllBindings() {
    var caseId = UUID.randomUUID();
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0.0")
            .stigmergyConfig(new StigmergyConfig(null, null, null))
            .build();
    var plan = new DefaultCasePlanModel(caseId);
    var ctx = new PlanExecutionContext(caseId, def, null, null, null, List.of(), null, null);
    var selected = strategy.select(plan, ctx, List.of(binding("b1"), binding("b2")));
    assertEquals(2, selected.size());
    assertTrue(coordinator.isStigmergyCase(caseId));
  }

  @Test
  void subsequentSelectReturnsEmpty() {
    var caseId = UUID.randomUUID();
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0.0")
            .stigmergyConfig(new StigmergyConfig(null, null, null))
            .build();
    var plan = new DefaultCasePlanModel(caseId);
    var ctx = new PlanExecutionContext(caseId, def, null, null, null, List.of(), null, null);
    strategy.select(plan, ctx, List.of(binding("b1")));
    var second = strategy.select(plan, ctx, List.of(binding("b1")));
    assertTrue(second.isEmpty());
  }

  private Binding binding(String name) {
    Capability cap = Capability.builder().name(name).inputSchema("{}").outputSchema("{}").build();
    return Binding.builder()
        .name(name)
        .capability(cap)
        .on(new ContextChangeTrigger("." + name))
        .build();
  }
}
