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

/**
 * Outcome of a worker execution.
 *
 * <p>Allows workers to signal semantic completion states beyond success/failure:
 *
 * <ul>
 *   <li>{@link Success} — normal completion
 *   <li>{@link Declined} — worker opted not to handle this work (guard-like)
 *   <li>{@link Failed} — worker attempted work but failed
 *   <li>{@link Expired} — worker exceeded its deadline
 * </ul>
 *
 * <p>Declined, Failed, and Expired map to distinct {@link WorkStatus} values ({@code DECLINED},
 * {@code FAILED}, {@code EXPIRED}), allowing case definitions to react differently to "not my job"
 * vs "job failed" vs "timed out".
 */
public sealed interface WorkerOutcome
    permits WorkerOutcome.Success,
        WorkerOutcome.Declined,
        WorkerOutcome.Failed,
        WorkerOutcome.Expired {

  record Success() implements WorkerOutcome {}

  record Declined(String reason) implements WorkerOutcome {}

  record Failed(String reason) implements WorkerOutcome {}

  record Expired(String reason) implements WorkerOutcome {}

  static WorkerOutcome success() {
    return new Success();
  }
}
