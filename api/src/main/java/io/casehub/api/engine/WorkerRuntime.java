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
package io.casehub.api.engine;

import io.casehub.api.context.CaseContext;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime environment for worker functions, enabling in-process choreography: spawning child
 * workers, awaiting their completion, and sequencing multi-step flows.
 */
public interface WorkerRuntime {

  /** Returns the case ID that this worker execution belongs to. */
  UUID caseId();

  /**
   * Execute a worker function in-process with the given input. Supports both Sync and Flow
   * variants. Returns immediately with the result — no external scheduling, no ledger trace.
   */
  WorkerResult execute(WorkerFunction function, Map<String, Object> input);

  /**
   * Execute a worker by name. Looks up the worker from the case definition and delegates to {@link
   * #execute(WorkerFunction, Map)}.
   */
  WorkerResult execute(String workerName, Map<String, Object> input);

  /** Spawn a new child case (detached) and return its ID. Non-blocking. */
  UUID spawnCase(String caseType, Map<String, Object> input);

  /**
   * Block until a child case reaches a terminal state (COMPLETED/FAULTED/CANCELLED). Throws {@link
   * SettlementTimeoutException} if the timeout expires.
   */
  CaseContext awaitCase(UUID childCaseId, Duration timeout);

  /**
   * Convenience: spawn then await. Equivalent to {@code awaitCase(spawnCase(caseType, input),
   * timeout)}.
   */
  CaseContext spawnAndAwaitCase(String caseType, Map<String, Object> input, Duration timeout);
}
