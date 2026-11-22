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
package io.casehub.api.model.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import org.junit.jupiter.api.Test;

class DeferredAgentConfigTest {

  @Test
  void slotConfig_requiresSlot() {
    assertThrows(
        NullPointerException.class,
        () -> new DeferredAgentConfig.SlotConfig(null, null, null, null, null));
  }

  @Test
  void slotConfig_acceptsSlotOnly() {
    var config = new DeferredAgentConfig.SlotConfig("my-slot", null, null, null, null);
    assertEquals("my-slot", config.slot());
    assertNull(config.model());
  }

  @Test
  void slotConfig_acceptsAllFields() {
    var config =
        new DeferredAgentConfig.SlotConfig(
            "my-slot", "claude-sonnet-5-5", "Analyse: {{text}}", ".input", ".output");
    assertEquals("my-slot", config.slot());
    assertEquals("claude-sonnet-5-5", config.model());
    assertEquals("Analyse: {{text}}", config.userMessageTemplate());
    assertEquals(".input", config.inputProjection());
    assertEquals(".output", config.outputProjection());
  }

  @Test
  void capabilityConfig_requiresCapability() {
    assertThrows(
        NullPointerException.class,
        () -> new DeferredAgentConfig.CapabilityConfig(null, null, null, null, null));
  }

  @Test
  void capabilityConfig_acceptsCapabilityOnly() {
    var config =
        new DeferredAgentConfig.CapabilityConfig("risk-assessment", null, null, null, null);
    assertEquals("risk-assessment", config.capability());
    assertNull(config.domain());
    assertNull(config.model());
  }

  @Test
  void capabilityConfig_acceptsAllFields() {
    var inputEval = new JQExpressionEvaluator(".input");
    var outputEval = new JQExpressionEvaluator(".output");
    var config =
        new DeferredAgentConfig.CapabilityConfig(
            "risk-assessment", "aml", "claude-opus-5-5", inputEval, outputEval);
    assertEquals("risk-assessment", config.capability());
    assertEquals("aml", config.domain());
    assertEquals("claude-opus-5-5", config.model());
    assertSame(inputEval, config.inputEvaluator());
    assertSame(outputEval, config.outputEvaluator());
  }

  @Test
  void patternMatch_exhaustive() {
    DeferredAgentConfig slot = new DeferredAgentConfig.SlotConfig("s", null, null, null, null);
    DeferredAgentConfig cap = new DeferredAgentConfig.CapabilityConfig("c", null, null, null, null);

    assertEquals("slot:s", describe(slot));
    assertEquals("cap:c", describe(cap));
  }

  private String describe(DeferredAgentConfig config) {
    return switch (config) {
      case DeferredAgentConfig.SlotConfig sc -> "slot:" + sc.slot();
      case DeferredAgentConfig.CapabilityConfig cc -> "cap:" + cc.capability();
    };
  }
}
