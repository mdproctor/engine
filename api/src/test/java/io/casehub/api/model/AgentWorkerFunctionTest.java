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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.casehub.api.model.ai.WorkerAgentInvoker;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.worker.api.WorkerFunction;
import io.smallrye.mutiny.Multi;
import java.time.Duration;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class AgentWorkerFunctionTest {

  @Test
  void implementsWorkerFunction() {
    var invoker = testInvoker();
    var fn = new AgentWorkerFunction(invoker);
    assertInstanceOf(WorkerFunction.class, fn);
    assertSame(invoker, fn.invoker());
  }

  @Test
  void rejectsNullInvoker() {
    assertThrows(NullPointerException.class, () -> new AgentWorkerFunction(null));
  }

  @Test
  void ofSlot_createsDeferred() {
    var config =
        new io.casehub.api.model.ai.DeferredAgentConfig.SlotConfig(
            "sentiment-analyst", null, null, null, null);
    var fn = AgentWorkerFunction.ofSlot(config);
    assertInstanceOf(WorkerFunction.class, fn);
    org.junit.jupiter.api.Assertions.assertTrue(fn.isDeferred());
    org.junit.jupiter.api.Assertions.assertEquals(config, fn.deferredConfig());
    org.junit.jupiter.api.Assertions.assertNull(fn.invoker());
  }

  @Test
  void eager_createsImmediate() {
    var fn = AgentWorkerFunction.eager(testInvoker());
    org.junit.jupiter.api.Assertions.assertFalse(fn.isDeferred());
    org.junit.jupiter.api.Assertions.assertNotNull(fn.invoker());
    org.junit.jupiter.api.Assertions.assertNull(fn.deferredConfig());
  }

  @Test
  void slotConfig_withOverrides() {
    var config =
        new io.casehub.api.model.ai.DeferredAgentConfig.SlotConfig(
            "my-slot", "claude-sonnet-5-5", "Analyse: {{text}}", ".input", ".output");
    org.junit.jupiter.api.Assertions.assertEquals("my-slot", config.slot());
    org.junit.jupiter.api.Assertions.assertEquals("claude-sonnet-5-5", config.model());
    org.junit.jupiter.api.Assertions.assertEquals(
        "Analyse: {{text}}", config.userMessageTemplate());
    org.junit.jupiter.api.Assertions.assertEquals(".input", config.inputProjection());
    org.junit.jupiter.api.Assertions.assertEquals(".output", config.outputProjection());
  }

  @Test
  void ofSlot_nullSlot_throws() {
    assertThrows(
        NullPointerException.class,
        () ->
            new io.casehub.api.model.ai.DeferredAgentConfig.SlotConfig(
                null, null, null, null, null));
  }

  private WorkerAgentInvoker testInvoker() {
    AgentProvider stubProvider =
        new AgentProvider() {
          @Override
          public Multi<AgentEvent> invoke(AgentSessionConfig config) {
            return Multi.createFrom().empty();
          }

          @Override
          public AgentSession openSession(AgentSessionInit init) {
            throw new UnsupportedOperationException();
          }
        };
    return new WorkerAgentInvoker(
        stubProvider,
        "test",
        null,
        UnaryOperator.identity(),
        UnaryOperator.identity(),
        null,
        null,
        Duration.ofSeconds(30));
  }
}
