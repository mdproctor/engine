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

import io.casehub.api.spi.QuorumConfig;
import io.casehub.api.spi.routing.CandidateSetSpec;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import jakarta.annotation.Nullable;
import java.util.Set;

public sealed interface CallerConfig
    permits CallerConfig.Human, CallerConfig.Llm, CallerConfig.A2A, CallerConfig.Any {

  record Human(
      @Nullable CandidateSetSpec candidateGroups,
      @Nullable CandidateSetSpec candidateUsers,
      @Nullable String title,
      @Nullable ExpressionEvaluator titleExpression,
      @Nullable Set<String> outcomes,
      @Nullable Integer claimDeadlineHours,
      @Nullable String scope,
      @Nullable ExpressionEvaluator scopeExpression,
      @Nullable String priority,
      @Nullable String templateRef,
      @Nullable Class<?> payloadType,
      @Nullable QuorumConfig quorum)
      implements CallerConfig {

    public Human {
      if (outcomes != null) {
        outcomes = Set.copyOf(outcomes);
      }
    }
  }

  record Llm(@Nullable String model, @Nullable String modelName, @Nullable String systemPrompt)
      implements CallerConfig {}

  record A2A(@Nullable String endpoint, @Nullable String skill, boolean streaming)
      implements CallerConfig {}

  record Any() implements CallerConfig {}
}
