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
package io.casehub.ledger.routing;

import io.casehub.ledger.api.model.ActorTrustScore.ScoreType;
import io.casehub.ledger.runtime.model.ActorTrustScore;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.casehub.ledger.runtime.service.routing.TrustScoreDeltaPayload;
import io.casehub.ledger.runtime.service.routing.TrustScoreFullPayload;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of CAPABILITY and CAPABILITY_DIMENSION trust scores, kept current by {@link
 * TrustScoreRoutingPublisher} CDI events fired after each ledger scoring cycle.
 *
 * <p>Annotated {@link Startup} so {@link #hydrate()} runs at application startup on the main thread
 * — not on first access on a Vert.x IO thread, which would block the event loop.
 *
 * <p>The publisher fires {@link TrustScoreFullPayload} and {@link TrustScoreDeltaPayload} via
 * synchronous {@code fire()} (not {@code fireAsync()}). Observers must use {@link Observes}, not
 * {@code ObservesAsync} — an async observer would not be discovered by the publisher's {@code
 * resolveObserverMethods()} check, causing the full payload event to be silently suppressed.
 *
 * <p>Delta payloads carry only GLOBAL scores (no {@code capabilityKey}/{@code dimensionKey}) —
 * {@link #onDelta} is a no-op. CAPABILITY and CAPABILITY_DIMENSION scores update only on full
 * payload events.
 *
 * <p><b>Identity assumption:</b> looks up scores using {@code workerId} (= {@code Worker.getName()}
 * from the case definition YAML) as the {@code actorId} key. Verify at deployment time that {@code
 * TrustScoreJob} populates {@code actorId} with the same string format as worker names. If the
 * namespaces diverge, every lookup returns {@code empty()} and routing silently falls to Phase 0
 * (availability routing) with no error.
 */
@Startup
@ApplicationScoped
public class TrustScoreCache {

  // key: "actorId:capabilityKey"
  private final ConcurrentHashMap<String, CachedCapabilityScore> capabilityScores =
      new ConcurrentHashMap<>();

  // key: "actorId:capabilityKey:dimensionKey"
  private final ConcurrentHashMap<String, Double> capabilityDimensionScores =
      new ConcurrentHashMap<>();

  private final ActorTrustScoreRepository trustRepo;

  @Inject
  public TrustScoreCache(final ActorTrustScoreRepository trustRepo) {
    this.trustRepo = trustRepo;
  }

  @PostConstruct
  public void hydrate() {
    trustRepo.findAll().forEach(this::index);
  }

  public void onFull(@Observes final TrustScoreFullPayload payload) {
    payload.scores().forEach(this::index);
  }

  // Delta carries only GLOBAL scores (no capabilityKey/dimensionKey) — no-op for this cache.
  // Consistency note: @Observes (not @ObservesAsync) — the publisher uses fire(), not fireAsync().
  public void onDelta(@Observes final TrustScoreDeltaPayload payload) {
    // intentional no-op — delta has no key structure for CAPABILITY/CAPABILITY_DIMENSION
  }

  private void index(final ActorTrustScore s) {
    if (s.scoreType == ScoreType.CAPABILITY && s.capabilityKey != null) {
      capabilityScores.put(
          s.actorId + ":" + s.capabilityKey,
          new CachedCapabilityScore(s.trustScore, s.decisionCount));
    } else if (s.scoreType == ScoreType.CAPABILITY_DIMENSION
        && s.capabilityKey != null
        && s.dimensionKey != null) {
      capabilityDimensionScores.put(
          s.actorId + ":" + s.capabilityKey + ":" + s.dimensionKey, s.trustScore);
    }
  }

  public OptionalDouble getCapabilityScore(final String actorId, final String capabilityKey) {
    final CachedCapabilityScore s = capabilityScores.get(actorId + ":" + capabilityKey);
    return s != null ? OptionalDouble.of(s.trustScore()) : OptionalDouble.empty();
  }

  /** Decision count for Phase 1 detection — 0 when no history exists (Phase 0). */
  public int getDecisionCount(final String actorId, final String capabilityKey) {
    final CachedCapabilityScore s = capabilityScores.get(actorId + ":" + capabilityKey);
    return s != null ? s.decisionCount() : 0;
  }

  public OptionalDouble getCapabilityDimensionScore(
      final String actorId, final String capabilityKey, final String dimensionKey) {
    final Double v =
        capabilityDimensionScores.get(actorId + ":" + capabilityKey + ":" + dimensionKey);
    return v != null ? OptionalDouble.of(v) : OptionalDouble.empty();
  }

  record CachedCapabilityScore(double trustScore, int decisionCount) {}
}
