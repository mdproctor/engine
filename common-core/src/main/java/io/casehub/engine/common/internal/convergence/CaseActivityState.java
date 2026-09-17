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
package io.casehub.engine.common.internal.convergence;

import java.time.Duration;
import java.time.Instant;

public final class CaseActivityState {

  private final SlidingWindowCounter dispatches;
  private final SlidingWindowCounter signalDeposits;
  private final SlidingWindowCounter contextMutations;
  private final SlidingWindowCounter evaluationCycles;

  public CaseActivityState(int maxWindowEntries) {
    this.dispatches = new SlidingWindowCounter(maxWindowEntries);
    this.signalDeposits = new SlidingWindowCounter(maxWindowEntries);
    this.contextMutations = new SlidingWindowCounter(maxWindowEntries);
    this.evaluationCycles = new SlidingWindowCounter(maxWindowEntries);
  }

  public void recordDispatch(Instant now) {
    dispatches.record(now);
  }

  public void recordSignalDeposit(Instant now) {
    signalDeposits.record(now);
  }

  public void recordContextMutation(Instant now, int keyCount) {
    for (int i = 0; i < keyCount; i++) contextMutations.record(now);
  }

  public void recordEvaluationCycle(Instant now) {
    evaluationCycles.record(now);
  }

  public long totalDispatches() {
    return dispatches.total();
  }

  public long totalSignalDeposits() {
    return signalDeposits.total();
  }

  public long totalContextMutations() {
    return contextMutations.total();
  }

  public long totalEvaluationCycles() {
    return evaluationCycles.total();
  }

  public double dispatchRate(Duration window, Instant now) {
    return dispatches.rate(window, now);
  }

  public double signalDepositRate(Duration window, Instant now) {
    return signalDeposits.rate(window, now);
  }

  public double contextMutationRate(Duration window, Instant now) {
    return contextMutations.rate(window, now);
  }

  public double evaluationRate(Duration window, Instant now) {
    return evaluationCycles.rate(window, now);
  }
}
