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

import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.util.Objects;

public sealed interface DeferredAgentConfig
    permits DeferredAgentConfig.SlotConfig, DeferredAgentConfig.CapabilityConfig {

  record SlotConfig(
      String slot,
      String model,
      String userMessageTemplate,
      String inputProjection,
      String outputProjection)
      implements DeferredAgentConfig {
    public SlotConfig {
      Objects.requireNonNull(slot, "slot is required");
    }
  }

  record CapabilityConfig(
      String capability,
      String domain,
      String model,
      ExpressionEvaluator inputEvaluator,
      ExpressionEvaluator outputEvaluator)
      implements DeferredAgentConfig {
    public CapabilityConfig {
      Objects.requireNonNull(capability, "capability is required");
    }
  }
}
