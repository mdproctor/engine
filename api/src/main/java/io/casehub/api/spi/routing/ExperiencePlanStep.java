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
package io.casehub.api.spi.routing;

import java.util.Map;
import java.util.Objects;

/**
 * A single step in a retrieved case's plan trace. Captures which binding was selected, the
 * capability it targeted, the worker that executed, the routing outcome, and the priority at which
 * it was selected.
 *
 * <p>Used by {@link RetrievedExperience} to record the execution path of a similar past case. CBR
 * routing strategies use the plan trace to predict which bindings are most likely to succeed for
 * the current case.
 *
 * @param bindingName the binding that was selected in this step
 * @param capabilityName the capability this binding targets
 * @param workerName the worker that executed (nullable — may be absent for humanTask/subCase
 *     bindings)
 * @param stepOutcome the routing outcome — one of {@link RoutingOutcome} enum values ({@code
 *     SUCCESS}, {@code FAILURE}, {@code GATE_REJECTED}, {@code GATE_EXPIRED}). Stored as the enum's
 *     {@code name()} string.
 * @param priority the selection priority (0-based — lower is higher priority)
 * @param parameters custom parameters for this step (empty map if none)
 */
public record ExperiencePlanStep(
    String bindingName,
    String capabilityName,
    String workerName,
    String stepOutcome,
    int priority,
    Map<String, Object> parameters) {

  public ExperiencePlanStep {
    Objects.requireNonNull(bindingName, "bindingName must not be null");
    Objects.requireNonNull(capabilityName, "capabilityName must not be null");
    if (priority < 0) {
      throw new IllegalArgumentException("priority must be non-negative, got: " + priority);
    }
    parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
  }
}
