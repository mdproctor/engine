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
package io.casehub.actorstate;

import io.casehub.platform.api.actor.ActorStateAccumulator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe accumulator for actor state data. Contributors run concurrently via ManagedExecutor.
 *
 * <p>{@link #markSucceeded(String)} and {@link #markFailed(String, String)} are package-private —
 * called by {@link ActorStateAggregator} only. Contributors never call these methods; they are not
 * on the {@link ActorStateAccumulator} interface.
 */
class ActorStateAccumulatorImpl implements ActorStateAccumulator {

  private final String actorId;
  private final AtomicReference<Double> trustScore = new AtomicReference<>(null);
  private final ConcurrentHashMap<String, Double> capabilityScores = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<ActorStateResponse.WorkItemSummary> workItems =
      new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<ActorStateResponse.CommitmentSummary> commitments =
      new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<UUID> engineActiveCaseIds = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<String> sources = new CopyOnWriteArrayList<>();
  private final ConcurrentHashMap<String, String> warnings = new ConcurrentHashMap<>();

  ActorStateAccumulatorImpl(final String actorId) {
    this.actorId = actorId;
  }

  @Override
  public void trustScore(final Double score) {
    trustScore.set(score);
  }

  @Override
  public void capabilityScore(final String capability, final double score) {
    capabilityScores.put(capability, score);
  }

  @Override
  public void workItem(
      final UUID id,
      final String title,
      final String status,
      final String category,
      final UUID caseId) {
    workItems.add(new ActorStateResponse.WorkItemSummary(id, title, status, category, caseId));
  }

  @Override
  public void commitment(
      final UUID commitmentId,
      final UUID channelId,
      final UUID caseId,
      final String state,
      final Instant expiresAt) {
    commitments.add(
        new ActorStateResponse.CommitmentSummary(
            commitmentId, channelId, caseId, state, expiresAt));
  }

  @Override
  public void engineActiveCaseId(final UUID caseId) {
    engineActiveCaseIds.add(caseId);
  }

  /** Called by ActorStateAggregator after contributor.contribute() succeeds. */
  void markSucceeded(final String sourceName) {
    sources.add(sourceName);
  }

  /** Called by ActorStateAggregator when contributor.contribute() throws. */
  void markFailed(final String sourceName, final String reason) {
    warnings.put(sourceName, reason);
  }

  ActorStateResponse build() {
    return new ActorStateResponse(
        actorId,
        Instant.now(),
        trustScore.get(),
        Collections.unmodifiableMap(new HashMap<>(capabilityScores)),
        new ArrayList<>(workItems),
        new ArrayList<>(commitments),
        new ArrayList<>(engineActiveCaseIds),
        new ArrayList<>(sources),
        warnings.isEmpty() ? null : Collections.unmodifiableMap(new HashMap<>(warnings)));
  }
}
