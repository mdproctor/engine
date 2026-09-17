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

import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ActivityTracker implements Resettable {

  static final int DEFAULT_MAX_WINDOW_ENTRIES = 600;
  private final ConcurrentHashMap<UUID, CaseActivityState> states = new ConcurrentHashMap<>();
  private volatile int maxWindowEntries = DEFAULT_MAX_WINDOW_ENTRIES;

  public void setMaxWindowEntries(int maxWindowEntries) {
    this.maxWindowEntries = maxWindowEntries;
  }

  public CaseActivityState getState(UUID caseId) {
    return states.computeIfAbsent(caseId, k -> new CaseActivityState(maxWindowEntries));
  }

  public void recordDispatch(UUID caseId) {
    getState(caseId).recordDispatch(Instant.now());
  }

  public void recordSignalDeposit(UUID caseId) {
    getState(caseId).recordSignalDeposit(Instant.now());
  }

  public void recordContextMutation(UUID caseId, int keyCount) {
    getState(caseId).recordContextMutation(Instant.now(), keyCount);
  }

  public void recordEvaluationCycle(UUID caseId) {
    getState(caseId).recordEvaluationCycle(Instant.now());
  }

  public void evictByCase(UUID caseId) {
    states.remove(caseId);
  }

  @Override
  public void reset() {
    states.clear();
  }
}
