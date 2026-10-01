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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

@ApplicationScoped
public class ImprovementOutcomeEventCapture {

  private final ImprovementOutcomeRecorder outcomeRecorder;
  private final ImprovementSignalProjector signalProjector;
  private final ImprovementCbrProjector cbrProjector;
  private final ImprovementBudgetEnforcer budgetEnforcer;
  private final ImprovementCategoryTracker categoryTracker;
  private final RegressionDetector regressionDetector;

  @Inject
  public ImprovementOutcomeEventCapture(
      ImprovementOutcomeRecorder outcomeRecorder,
      ImprovementSignalProjector signalProjector,
      ImprovementCbrProjector cbrProjector,
      ImprovementBudgetEnforcer budgetEnforcer,
      ImprovementCategoryTracker categoryTracker,
      RegressionDetector regressionDetector) {
    this.outcomeRecorder = outcomeRecorder;
    this.signalProjector = signalProjector;
    this.cbrProjector = cbrProjector;
    this.budgetEnforcer = budgetEnforcer;
    this.categoryTracker = categoryTracker;
    this.regressionDetector = regressionDetector;
  }

  public ImprovementOutcomeEventCapture(
      ImprovementOutcomeRecorder outcomeRecorder,
      ImprovementSignalProjector signalProjector,
      ImprovementCbrProjector cbrProjector,
      ImprovementBudgetEnforcer budgetEnforcer) {
    this(
        outcomeRecorder,
        signalProjector,
        cbrProjector,
        budgetEnforcer,
        new ImprovementCategoryTracker(),
        new RegressionDetector(
            new RegressionEvaluatorRegistry(),
            new ImprovementCategoryRegistry(),
            new ImprovementCategoryTracker(),
            new RollbackHistory(),
            new HealthScoreTracker(new CapabilityAreaRegistry()),
            new NoOpEvent<>()));
  }

  public void onImprovementComplete(@ObservesAsync ImprovementCaseCompleted event) {
    var outcome = event.outcome();
    outcomeRecorder.record(event.caseId(), event.tenancyId(), outcome);
    signalProjector.project(event.caseId(), outcome);
    cbrProjector.project(event.tenancyId(), outcome);
    budgetEnforcer.recordCompletion(outcome.improvementCaseId());
    categoryTracker.recordOutcome(event.caseId(), outcome.category(), outcome.status());
    regressionDetector.onOutcome(event.caseId(), outcome);
  }
}
