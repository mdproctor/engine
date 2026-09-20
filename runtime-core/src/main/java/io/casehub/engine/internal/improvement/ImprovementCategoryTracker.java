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
import io.casehub.engine.common.spi.Resettable;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ImprovementCategoryTracker implements Resettable {

  private static final int SUPPRESSION_THRESHOLD = 3;

  public record CategoryState(
      int successCount,
      int failureCount,
      int rejectionCount,
      Instant lastOutcome,
      boolean paused,
      @Nullable Instant pausedUntil) {}

  private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, CategoryState>> states =
      new ConcurrentHashMap<>();

  public void recordOutcome(UUID caseId, String category, ImprovementOutcome.OutcomeStatus status) {
    states.computeIfAbsent(caseId, k -> new ConcurrentHashMap<>());
    states
        .get(caseId)
        .compute(
            category,
            (k, existing) -> {
              int success = existing != null ? existing.successCount() : 0;
              int failure = existing != null ? existing.failureCount() : 0;
              int rejection = existing != null ? existing.rejectionCount() : 0;
              boolean paused = existing != null && existing.paused();
              Instant pausedUntil = existing != null ? existing.pausedUntil() : null;

              return switch (status) {
                case MERGED ->
                    new CategoryState(
                        success + 1, 0, rejection, Instant.now(), paused, pausedUntil);
                case FAILED ->
                    new CategoryState(
                        success, failure + 1, rejection, Instant.now(), paused, pausedUntil);
                case REJECTED ->
                    new CategoryState(
                        success, failure, rejection + 1, Instant.now(), paused, pausedUntil);
                case REGRESSION, ABANDONED ->
                    new CategoryState(
                        success, failure, rejection, Instant.now(), paused, pausedUntil);
              };
            });
  }

  public boolean isSuppressed(UUID caseId, String category) {
    var caseStates = states.get(caseId);
    if (caseStates == null) return false;
    var state = caseStates.get(category);
    if (state == null) return false;

    if (state.paused()) {
      if (state.pausedUntil() != null && Instant.now().isAfter(state.pausedUntil())) {
        unpauseCategory(caseId, category);
        return false;
      }
      return true;
    }

    if (state.failureCount() >= SUPPRESSION_THRESHOLD) return true;
    if (state.rejectionCount() >= SUPPRESSION_THRESHOLD) return true;

    return false;
  }

  public void pauseCategory(UUID caseId, String category, Duration duration) {
    states.computeIfAbsent(caseId, k -> new ConcurrentHashMap<>());
    states
        .get(caseId)
        .compute(
            category,
            (k, existing) -> {
              int success = existing != null ? existing.successCount() : 0;
              int failure = existing != null ? existing.failureCount() : 0;
              int rejection = existing != null ? existing.rejectionCount() : 0;
              Instant lastOutcome = existing != null ? existing.lastOutcome() : Instant.now();
              return new CategoryState(
                  success, failure, rejection, lastOutcome, true, Instant.now().plus(duration));
            });
  }

  public void unpauseCategory(UUID caseId, String category) {
    var caseStates = states.get(caseId);
    if (caseStates == null) return;
    caseStates.computeIfPresent(
        category,
        (k, existing) ->
            new CategoryState(
                existing.successCount(),
                existing.failureCount(),
                existing.rejectionCount(),
                existing.lastOutcome(),
                false,
                null));
  }

  @Override
  public void reset() {
    states.clear();
  }
}
