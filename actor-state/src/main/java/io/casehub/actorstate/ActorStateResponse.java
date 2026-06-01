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

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response body for GET /actors/{actorId}/state.
 *
 * <p>{@code trustScore: null} means the actor has no computed score yet (not zero trust). {@code
 * sourceWarnings} is absent (not {}) when all sources succeeded. {@code engineActiveCaseIds} is
 * scoped to active Quartz jobs only — not exhaustive.
 */
public record ActorStateResponse(
    String actorId,
    Instant retrievedAt,
    Double trustScore,
    Map<String, Double> capabilityScores,
    List<WorkItemSummary> activeWorkItems,
    List<CommitmentSummary> openCommitments,
    List<UUID> engineActiveCaseIds,
    List<String> sources,
    @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> sourceWarnings) {

  /** Summary of an active WorkItem assigned to or owned by the actor. */
  public record WorkItemSummary(
      UUID id, String title, String status, String category, UUID caseId) {}

  /** Summary of an open Commitment where this actor is the obligor. */
  public record CommitmentSummary(
      UUID commitmentId, UUID channelId, UUID caseId, String state, Instant expiresAt) {}
}
