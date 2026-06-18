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
package io.casehub.api.model;

import java.util.Map;
import java.util.UUID;

/**
 * Result of orchestrated work returned by {@code WorkOrchestrator.submit()}. The {@code
 * correlationKey} is the idempotency hash used to match this result to its submission.
 *
 * <p>{@code caseId} is set when the result is produced by the case engine and identifies the case
 * that owned the worker. Listeners can use it for precise per-case lookups. Null when the result is
 * produced outside the engine context (e.g. direct WorkOrchestrator calls).
 */
public record WorkResult(
    String correlationKey,
    WorkStatus status,
    Map<String, Object> output,
    String workerId,
    UUID caseId) {

  public static WorkResult completed(
      String correlationKey, Map<String, Object> output, String workerId) {
    return new WorkResult(correlationKey, WorkStatus.COMPLETED, output, workerId, null);
  }

  public static WorkResult completed(
      String correlationKey, Map<String, Object> output, String workerId, UUID caseId) {
    return new WorkResult(correlationKey, WorkStatus.COMPLETED, output, workerId, caseId);
  }

  public static WorkResult faulted(String correlationKey, String workerId) {
    return new WorkResult(correlationKey, WorkStatus.FAULTED, Map.of(), workerId, null);
  }

  public static WorkResult faulted(String correlationKey, String workerId, UUID caseId) {
    return new WorkResult(correlationKey, WorkStatus.FAULTED, Map.of(), workerId, caseId);
  }

  public static WorkResult declined(String correlationKey, String workerId, UUID caseId) {
    return new WorkResult(correlationKey, WorkStatus.DECLINED, Map.of(), workerId, caseId);
  }

  public static WorkResult failed(String correlationKey, String workerId, UUID caseId) {
    return new WorkResult(correlationKey, WorkStatus.FAILED, Map.of(), workerId, caseId);
  }

  public static WorkResult expired(String correlationKey, String workerId, UUID caseId) {
    return new WorkResult(correlationKey, WorkStatus.EXPIRED, Map.of(), workerId, caseId);
  }
}
