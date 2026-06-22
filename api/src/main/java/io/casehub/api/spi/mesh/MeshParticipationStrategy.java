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
package io.casehub.api.spi.mesh;

import java.util.UUID;

/**
 * SPI: declares how actively an agent participates in the CaseHub mesh.
 *
 * <p>The participation level is consulted by {@code WorkerContextProvider} implementations to
 * determine which channels to surface in the agent's system prompt.
 *
 * <p>Note: {@code strategyFor} receives {@code caseId} directly. Null is valid — the strategy may
 * be consulted before a case identifier is available.
 *
 * <p>Standard implementations: {@link ActiveParticipationStrategy}, {@link
 * ReactiveParticipationStrategy}, {@link SilentParticipationStrategy}. Select via {@link
 * #named(String)} for config-driven choice.
 */
public interface MeshParticipationStrategy {

  /**
   * Returns the participation level for the given worker.
   *
   * @param workerId the worker identifier; may be {@code null} or empty
   * @param caseId the case identifier; may be {@code null} if not yet available
   */
  MeshParticipation strategyFor(String workerId, UUID caseId);

  /**
   * Factory for standard participation strategies by config string.
   *
   * <p>Valid values: {@code "active"} → {@link ActiveParticipationStrategy}, {@code "reactive"} →
   * {@link ReactiveParticipationStrategy}, {@code "silent"} → {@link SilentParticipationStrategy}.
   *
   * @throws IllegalArgumentException for unknown config values
   */
  static MeshParticipationStrategy named(final String configValue) {
    if (configValue == null) {
      throw new IllegalArgumentException("Mesh participation config value must not be null");
    }
    return switch (configValue) {
      case "active" -> new ActiveParticipationStrategy();
      case "reactive" -> new ReactiveParticipationStrategy();
      case "silent" -> new SilentParticipationStrategy();
      default -> throw new IllegalArgumentException("Unknown mesh participation: " + configValue);
    };
  }

  /** Participation level for an agent in the CaseHub mesh. */
  enum MeshParticipation {
    /** Register on startup, post STATUS, check messages periodically. */
    ACTIVE,
    /** Do not register; only engage when directly addressed. */
    REACTIVE,
    /** No mesh participation. */
    SILENT
  }
}
