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
package io.casehub.engine.internal.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.casehub.api.spi.observation.ContextSnapshot;
import io.casehub.api.spi.observation.EnvironmentObserver;
import io.casehub.api.spi.observation.Observation;
import io.casehub.api.spi.observation.ObservationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class TemporalSequenceObserver implements EnvironmentObserver {

  public record SequenceStep(String key, String valuePredicate) {}

  private final List<SequenceStep> steps;
  private final Duration window;
  private final Set<String> watched;

  private TemporalSequenceObserver(List<SequenceStep> steps, Duration window) {
    this.steps = List.copyOf(steps);
    this.window = window;
    this.watched = steps.stream().map(SequenceStep::key).collect(Collectors.toUnmodifiableSet());
  }

  public static TemporalSequenceObserver of(List<SequenceStep> steps, Duration window) {
    if (steps == null || steps.size() < 2) {
      throw new IllegalArgumentException("Temporal sequence requires at least 2 steps");
    }
    return new TemporalSequenceObserver(steps, window);
  }

  @Override
  public String observerType() {
    return "temporal-sequence";
  }

  @Override
  public Set<String> watchedKeys() {
    return watched;
  }

  @Override
  public List<Observation> observe(ObservationContext ctx) {
    List<ContextSnapshot> history = ctx.history();
    if (history == null || history.isEmpty()) {
      return List.of();
    }

    List<Instant> matchedAt = new ArrayList<>();
    int stepIndex = 0;
    Instant windowStart = null;

    for (ContextSnapshot snap : history) {
      if (stepIndex >= steps.size()) {
        break;
      }
      SequenceStep currentStep = steps.get(stepIndex);
      if (snap.changedKeys().contains(currentStep.key())) {
        if (stepIndex == 0) {
          windowStart = snap.timestamp();
        }
        if (windowStart != null
            && Duration.between(windowStart, snap.timestamp()).compareTo(window) > 0) {
          stepIndex = 0;
          matchedAt.clear();
          if (snap.changedKeys().contains(steps.get(0).key())) {
            windowStart = snap.timestamp();
            matchedAt.add(snap.timestamp());
            stepIndex = 1;
          } else {
            windowStart = null;
          }
          continue;
        }
        matchedAt.add(snap.timestamp());
        stepIndex++;
      }
    }

    if (stepIndex < steps.size()) {
      return List.of();
    }

    Map<String, JsonNode> details = new LinkedHashMap<>();
    details.put("window", TextNode.valueOf(window.toString()));
    com.fasterxml.jackson.databind.node.ArrayNode matchedArray =
        new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
    for (Instant ts : matchedAt) {
      matchedArray.add(ts.toString());
    }
    details.put("matchedAt", matchedArray);
    return List.of(new Observation("temporal-sequence", 1.0, details, Instant.now()));
  }
}
