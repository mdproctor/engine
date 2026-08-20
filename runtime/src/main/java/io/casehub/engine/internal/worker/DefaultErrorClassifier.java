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

import io.casehub.api.model.RecoveryLevel;
import io.casehub.api.spi.recovery.ErrorClassificationContext;
import io.casehub.api.spi.recovery.ErrorClassifier;
import io.casehub.worker.api.WorkerOutcome;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class DefaultErrorClassifier implements ErrorClassifier {

  private static final int ESCALATION_THRESHOLD = 3;

  @Override
  public RecoveryLevel classify(ErrorClassificationContext context) {
    if (context.attemptCount() >= ESCALATION_THRESHOLD) {
      return RecoveryLevel.REASONING;
    }
    RecoveryLevel level;
    if (context.hint() != null) {
      level =
          switch (context.hint()) {
            case TRANSIENT -> RecoveryLevel.TRANSIENT;
            case REASONING -> RecoveryLevel.REASONING;
            case FUNDAMENTAL -> RecoveryLevel.FUNDAMENTAL;
          };
    } else {
      level = classifyByOutcome(context.outcome());
    }
    if (level == RecoveryLevel.TRANSIENT && isNonIdempotent(context)) {
      return RecoveryLevel.REASONING;
    }
    return level;
  }

  private RecoveryLevel classifyByOutcome(WorkerOutcome<?> outcome) {
    return switch (outcome) {
      case WorkerOutcome.Expired<?> e -> RecoveryLevel.TRANSIENT;
      case WorkerOutcome.Declined<?> d -> RecoveryLevel.REASONING;
      case WorkerOutcome.Failed<?> f -> classifyFailedReason(f.reason());
      default -> RecoveryLevel.REASONING;
    };
  }

  private RecoveryLevel classifyFailedReason(String reason) {
    if (reason == null) return RecoveryLevel.REASONING;
    String lower = reason.toLowerCase();
    if (lower.contains("timeout")
        || lower.contains("connection refused")
        || lower.contains("503")
        || lower.contains("429")
        || lower.contains("socket")
        || lower.contains("econnreset")) {
      return RecoveryLevel.TRANSIENT;
    }
    return RecoveryLevel.REASONING;
  }

  private boolean isNonIdempotent(ErrorClassificationContext context) {
    if (context.definition() == null || context.bindingName() == null) {
      return false;
    }
    return context.definition().getBindings().stream()
        .filter(b -> context.bindingName().equals(b.getName()))
        .findFirst()
        .map(
            b ->
                b.getSideEffectClassification()
                    == io.casehub.api.model.SideEffectClassification.NON_IDEMPOTENT)
        .orElse(false);
  }

  @Override
  public String id() {
    return "heuristic";
  }
}
