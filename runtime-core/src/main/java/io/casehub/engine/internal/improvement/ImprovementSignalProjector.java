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
package io.casehub.engine.internal.improvement;

import io.casehub.api.model.stigmergy.ImprovementOutcome;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.UUID;

@ApplicationScoped
public class ImprovementSignalProjector {

  private static final Duration OUTCOME_SIGNAL_HALF_LIFE = Duration.ofHours(4);

  private final SignalRegistry signalRegistry;

  @Inject
  public ImprovementSignalProjector(SignalRegistry signalRegistry) {
    this.signalRegistry = signalRegistry;
  }

  public void project(UUID caseId, ImprovementOutcome outcome) {
    String signalName = outcomeSignalName(outcome.status());
    signalRegistry.deposit(
        caseId, signalName, 1.0, OUTCOME_SIGNAL_HALF_LIFE, "improvement-system", 100);
  }

  private static String outcomeSignalName(ImprovementOutcome.OutcomeStatus status) {
    return switch (status) {
      case MERGED -> "improvement:outcome:positive:pr-merged";
      case REJECTED -> "improvement:outcome:rejected";
      case REGRESSION -> "improvement:outcome:regression";
      case FAILED -> "improvement:outcome:failed";
      case ABANDONED -> "improvement:outcome:abandoned";
    };
  }
}
