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
package io.casehub.api.model.stigmergy;

import jakarta.annotation.Nullable;

public record ProvisionBudget(
    @Nullable Integer maxProvisions,
    @Nullable Integer maxConcurrent,
    @Nullable Integer cooldownCycles,
    @Nullable Double idleThreshold,
    @Nullable Integer idleGraceCycles) {

  public int effectiveMaxProvisions() {
    return maxProvisions != null ? maxProvisions : 50;
  }

  public int effectiveMaxConcurrent() {
    return maxConcurrent != null ? maxConcurrent : 10;
  }

  public int effectiveCooldownCycles() {
    return cooldownCycles != null ? cooldownCycles : 5;
  }

  public double effectiveIdleThreshold() {
    return idleThreshold != null ? idleThreshold : 0.01;
  }

  public int effectiveIdleGraceCycles() {
    return idleGraceCycles != null ? idleGraceCycles : 10;
  }
}
