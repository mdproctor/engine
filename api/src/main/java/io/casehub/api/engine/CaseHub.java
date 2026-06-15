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

import io.casehub.api.model.CaseDefinition;
import jakarta.inject.Inject;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public abstract class CaseHub {

  @Inject CaseHubRuntime runtime;

  public abstract CaseDefinition getDefinition();

  public CompletionStage<UUID> startCase() {
    return runtime.startCase(getDefinition());
  }

  /**
   * Start a case with arbitrary serializable input.
   *
   * <p>Accepts any Jackson-serializable object (POJO, {@code Map<String, Object>}, etc.). The input
   * is converted to the case context via {@link com.fasterxml.jackson.databind.ObjectMapper}. If a
   * {@code Map} is passed, its value types must be JSON-compatible (i.e., not typed collections
   * with non-Object values) — a raw {@code Map<String, Object>} passes through as-is.
   */
  public CompletionStage<UUID> startCase(Object inputData) {
    return runtime.startCase(getDefinition(), inputData);
  }

  public CompletionStage<Void> signal(UUID caseId, String path, Object value) {
    return runtime.signal(caseId, path, value);
  }

  public void cancelCase(UUID caseId) {
    runtime.cancelCase(caseId);
  }

  public void suspendCase(UUID caseId) {
    runtime.suspendCase(caseId);
  }

  public void resumeCase(UUID caseId) {
    runtime.resumeCase(caseId);
  }

  public CompletionStage<Object> query(UUID caseId, String path) {
    return runtime.query(caseId, path);
  }

  public <T> CompletionStage<T> query(UUID caseId, String path, Class<T> clazz) {
    return runtime.query(caseId, path, clazz);
  }
}
