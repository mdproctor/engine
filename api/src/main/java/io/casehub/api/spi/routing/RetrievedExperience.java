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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A retrieved case experience from the CBR memory store. Represents a past case with a similar
 * problem to the current case, including the solution that was applied, the outcome achieved, and
 * the full plan trace showing which bindings were selected.
 *
 * <p>Routing strategies use retrieved experiences to predict which bindings and workers are most
 * likely to succeed for the current case based on similarity to past cases.
 *
 * @param problem the problem description from the past case
 * @param solution the solution that was applied
 * @param outcome the final case outcome (COMPLETED, FAULTED, etc.)
 * @param confidence the quality/success score of the outcome (0.0-1.0, nullable)
 * @param similarityScore how similar this past case is to the current case (-1.0 to 1.0)
 * @param features extracted features from the past case (empty map if none)
 * @param planTrace the sequence of plan steps that were executed (empty list if none)
 */
public record RetrievedExperience(
    String problem,
    String solution,
    String outcome,
    Double confidence,
    double similarityScore,
    Map<String, Object> features,
    List<ExperiencePlanStep> planTrace) {

  public RetrievedExperience {
    Objects.requireNonNull(problem, "problem must not be null");
    Objects.requireNonNull(solution, "solution must not be null");
    if (similarityScore < -1.0 || similarityScore > 1.0) {
      throw new IllegalArgumentException(
          "similarityScore must be in range [-1.0, 1.0], got: " + similarityScore);
    }
    features = features != null ? Map.copyOf(features) : Map.of();
    planTrace = planTrace != null ? List.copyOf(planTrace) : List.of();
  }
}
