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

import io.casehub.api.context.PropagationContext;
import io.casehub.api.spi.routing.RetrievedExperience;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Context handed to a new worker at startup.
 *
 * <p>Built by {@code WorkerContextProvider} from CaseLedgerEntry history. Contains the task
 * description, the case identifier, the channels open for the case, ordered summaries of prior
 * workers, the propagation context for tracing, arbitrary backend-specific properties, and
 * retrieved CBR experiences from similar past cases.
 *
 * <p>{@code channels}, {@code priorWorkers}, {@code properties}, and {@code experiences} default to
 * empty collections when {@code null} is supplied and are always immutable.
 */
public record WorkerContext(
    String taskDescription,
    UUID caseId,
    List<CaseChannel> channels,
    List<WorkerSummary> priorWorkers,
    PropagationContext propagationContext,
    Map<String, Object> properties,
    List<RetrievedExperience> experiences) {

  public WorkerContext {
    channels = channels == null ? List.of() : List.copyOf(channels);
    priorWorkers = priorWorkers == null ? List.of() : List.copyOf(priorWorkers);
    properties =
        properties == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    experiences = experiences == null ? List.of() : List.copyOf(experiences);
  }

  public WorkerContext(
      String taskDescription,
      UUID caseId,
      List<CaseChannel> channels,
      List<WorkerSummary> priorWorkers,
      PropagationContext propagationContext,
      Map<String, Object> properties) {
    this(
        taskDescription, caseId, channels, priorWorkers, propagationContext, properties, List.of());
  }
}
