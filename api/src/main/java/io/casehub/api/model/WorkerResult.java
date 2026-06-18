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

import io.casehub.api.spi.PlannedAction;
import java.util.Map;

/**
 * The return type for all worker functions.
 *
 * <p>Workers without consequential actions use {@link #of(Map)} — behaviour is unchanged. Workers
 * proposing a consequential action include a {@link PlannedAction} via {@link #of(Map,
 * PlannedAction)}.
 *
 * <p>The {@code outcome} field allows workers to signal declined or failed execution. When outcome
 * is not {@link WorkerOutcome.Success}, {@code plannedAction} must be null (validated in compact
 * constructor).
 */
public record WorkerResult(
    Map<String, Object> output, PlannedAction plannedAction, WorkerOutcome outcome) {

  public WorkerResult {
    if (!(outcome instanceof WorkerOutcome.Success) && plannedAction != null) {
      throw new IllegalArgumentException(
          "plannedAction must be null when outcome is not Success (got: "
              + outcome.getClass().getSimpleName()
              + ")");
    }
  }

  public static WorkerResult of(final Map<String, Object> output) {
    return new WorkerResult(output, null, WorkerOutcome.success());
  }

  public static WorkerResult of(final Map<String, Object> output, final PlannedAction action) {
    return new WorkerResult(output, action, WorkerOutcome.success());
  }

  public static WorkerResult declined(final String reason) {
    return new WorkerResult(Map.of(), null, new WorkerOutcome.Declined(reason));
  }

  public static WorkerResult declined(
      final String reason, final Map<String, Object> partialOutput) {
    return new WorkerResult(partialOutput, null, new WorkerOutcome.Declined(reason));
  }

  public static WorkerResult failed(final String reason) {
    return new WorkerResult(Map.of(), null, new WorkerOutcome.Failed(reason));
  }

  public static WorkerResult failed(final String reason, final Map<String, Object> partialOutput) {
    return new WorkerResult(partialOutput, null, new WorkerOutcome.Failed(reason));
  }

  public static WorkerResult expired(final String reason) {
    return new WorkerResult(Map.of(), null, new WorkerOutcome.Expired(reason));
  }

  public static WorkerResult expired(final String reason, final Map<String, Object> partialOutput) {
    return new WorkerResult(partialOutput, null, new WorkerOutcome.Expired(reason));
  }
}
