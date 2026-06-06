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
 * <p>Replaces {@code Map<String, Object>} as the function/agent return type. Workers without
 * consequential actions use {@link #of(Map)} — behaviour is unchanged. Workers proposing a
 * consequential action include a {@link PlannedAction} via {@link #of(Map, PlannedAction)}.
 *
 * <p>{@link PlannedAction} is optional. If present, the engine calls {@link
 * io.casehub.api.spi.ReactiveActionRiskClassifier#classify(PlannedAction)} before applying output
 * to the case context. {@code WorkerResult} is unpacked at the {@code QuartzWorkerExecutionJob}
 * boundary; it does not propagate as a type beyond the job.
 */
public record WorkerResult(Map<String, Object> output, PlannedAction plannedAction) {

  public static WorkerResult of(final Map<String, Object> output) {
    return new WorkerResult(output, null);
  }

  public static WorkerResult of(final Map<String, Object> output, final PlannedAction action) {
    return new WorkerResult(output, action);
  }
}
