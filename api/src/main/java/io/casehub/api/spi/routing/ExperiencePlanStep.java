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

public record ExperiencePlanStep(
    String bindingName,
    String capabilityName,
    String workerName,
    RoutingOutcome stepOutcome,
    int priority,
    Map<String, Object> parameters,
    String adaptationAction,
    String adaptationReason) {

  public ExperiencePlanStep {
    Objects.requireNonNull(bindingName, "bindingName must not be null");
    if (priority < 0) {
      throw new IllegalArgumentException("priority must be non-negative, got: " + priority);
    }
    parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
  }

  public ExperiencePlanStep(
      String bindingName,
      String capabilityName,
      String workerName,
      RoutingOutcome stepOutcome,
      int priority,
      Map<String, Object> parameters) {
    this(bindingName, capabilityName, workerName, stepOutcome, priority, parameters, null, null);
  }
}
