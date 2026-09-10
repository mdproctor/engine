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
package io.casehub.engine.internal.worker;

import io.casehub.api.model.FailureCategory;
import io.casehub.api.spi.FailureClassificationContext;
import io.casehub.api.spi.FailureClassifier;
import io.casehub.worker.api.WorkerOutcome;
import java.util.List;
import java.util.Locale;

public class DefaultFailureClassifier implements FailureClassifier {

  private static final List<String> TRANSIENT_PATTERNS =
      List.of("timeout", "timed out", "connection refused", "503", "429", "retry");

  private static final List<String> KNOWLEDGE_PATTERNS =
      List.of("not found", "missing", "unsupported", "invalid schema");

  @Override
  public FailureCategory classify(WorkerOutcome<?> outcome, FailureClassificationContext context) {
    if (context.attemptCount() >= context.maxRerouteAttempts()) {
      return new FailureCategory.Infeasible(extractReason(outcome));
    }
    return switch (outcome) {
      case WorkerOutcome.Expired<?> e -> new FailureCategory.Transient(e.reason());
      case WorkerOutcome.Declined<?> d -> new FailureCategory.Knowledge(d.reason(), null);
      case WorkerOutcome.Failed<?> f -> classifyFailure(f);
      default -> new FailureCategory.Transient("unknown");
    };
  }

  private FailureCategory classifyFailure(WorkerOutcome.Failed<?> failed) {
    String reason = failed.reason();
    if (reason == null) return new FailureCategory.Transient("unknown");
    String lower = reason.toLowerCase(Locale.ROOT);
    for (String pattern : TRANSIENT_PATTERNS) {
      if (lower.contains(pattern)) return new FailureCategory.Transient(reason);
    }
    for (String pattern : KNOWLEDGE_PATTERNS) {
      if (lower.contains(pattern)) return new FailureCategory.Knowledge(reason, null);
    }
    return new FailureCategory.Transient(reason);
  }

  private String extractReason(WorkerOutcome<?> outcome) {
    return switch (outcome) {
      case WorkerOutcome.Expired<?> e -> e.reason();
      case WorkerOutcome.Declined<?> d -> d.reason();
      case WorkerOutcome.Failed<?> f -> f.reason();
      default -> "unknown";
    };
  }
}
