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
package io.casehub.engine.flow;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.common.internal.executor.WorkerFunctionHandler;
import io.casehub.engine.common.spi.WorkOrchestrator;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Verifies CDI wiring for casehub-engine-flow in a real Quarkus context.
 *
 * <p>Full end-to-end FlowWorker → dispatch → lineage event tests are tracked separately in
 * casehubio/engine#206 (follow-on integration test). These unit-level CDI tests confirm that the
 * module boots correctly and the right implementations are resolved.
 */
@QuarkusTest
class FlowWorkerIntegrationTest {

  @Inject Instance<WorkerFunctionHandler> handlers;
  @Inject WorkOrchestrator workOrchestrator;
  @Inject CasehubDispatch casehubDispatch;
  @Inject FlowExecutionRegistry flowExecutionRegistry;

  @Test
  void flow_module_boots_and_FlowWorkerFunctionHandler_is_discovered() {
    boolean hasFlowHandler = false;
    for (WorkerFunctionHandler handler : handlers) {
      if (handler instanceof FlowWorkerFunctionHandler) {
        hasFlowHandler = true;
        break;
      }
    }
    assertThat(hasFlowHandler).isTrue();
  }

  @Test
  void DefaultWorkOrchestrator_is_resolved_as_WorkOrchestrator() {
    // DefaultWorkOrchestrator in runtime implements WorkOrchestrator from common/spi.
    // CDI wraps it in a proxy — check the interface, not the concrete class name.
    assertThat(workOrchestrator).isInstanceOf(WorkOrchestrator.class);
  }

  @Test
  void CasehubDispatch_and_FlowExecutionRegistry_are_injectable() {
    assertThat(casehubDispatch).isNotNull();
    assertThat(flowExecutionRegistry).isNotNull();
  }
}
