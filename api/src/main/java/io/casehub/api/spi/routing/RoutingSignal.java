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

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Structured per-candidate scoring data returned by a {@link RoutingSignalProvider}.
 *
 * <p>Each candidate signal carries a score in [0.0, 1.0] and an optional reason. {@link
 * RoutingSignalAssembler} enforces the score range by clamping out-of-range values.
 *
 * <p>Signal maps may be sparse — only candidates the provider has data for need entries. Missing
 * entries contribute +0 to the final score (absence of data, not evaluated-as-zero).
 *
 * @param candidates per-candidate scoring data, keyed by worker ID
 */
public record RoutingSignal(Map<String, CandidateSignal> candidates) {

  public RoutingSignal {
    Objects.requireNonNull(candidates, "candidates must not be null");
    candidates = Map.copyOf(candidates);
  }

    public sealed interface CandidateSignal {
        record Score(double value, @Nullable String rationale) implements CandidateSignal {}

        record Exclude(String reason) implements CandidateSignal {}

        record Escalate(EscalationReason reason, String rationale) implements CandidateSignal {}
    }
}
