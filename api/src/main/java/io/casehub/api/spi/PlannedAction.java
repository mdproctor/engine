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
package io.casehub.api.spi;

import java.util.Map;
import java.util.UUID;

/**
 * A consequential action a worker intends to take before the engine advances the case.
 *
 * <p>Workers create instances via {@link #of(String, String, Map)} and include them in {@link
 * io.casehub.api.model.WorkerResult}. The engine enriches the instance with {@code workerId} and
 * {@code caseId} via {@link #withIdentity(String, UUID)} before passing it to {@link
 * ActionRiskClassifier#classify(PlannedAction)}.
 *
 * <p>Classifiers always receive a fully populated instance — {@code workerId} and {@code caseId}
 * are non-null at classify time.
 */
public record PlannedAction(
    String workerId,
    UUID caseId,
    String description,
    String actionType,
    Map<String, Object> context) {

  /** Worker-facing factory — workerId and caseId populated by engine before classify(). */
  public static PlannedAction of(
      final String description, final String actionType, final Map<String, Object> context) {
    return new PlannedAction(null, null, description, actionType, context);
  }

  /** Engine enrichment — called just before passing to the classifier. */
  public PlannedAction withIdentity(final String workerId, final UUID caseId) {
    return new PlannedAction(workerId, caseId, description, actionType, context);
  }
}
