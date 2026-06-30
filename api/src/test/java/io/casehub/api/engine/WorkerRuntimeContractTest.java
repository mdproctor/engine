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

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerRuntimeContractTest {

  @Test
  void anonymousImplementationCompiles() {
    WorkerRuntime runtime =
        new WorkerRuntime() {
          @Override
          public UUID caseId() {
            return UUID.randomUUID();
          }

          @Override
          public WorkerResult execute(WorkerFunction function, Map<String, Object> input) {
            return WorkerResult.of(Map.of());
          }

          @Override
          public WorkerResult execute(String workerName, Map<String, Object> input) {
            return WorkerResult.of(Map.of());
          }

          @Override
          public UUID spawnCase(String caseType, Map<String, Object> input) {
            return UUID.randomUUID();
          }

          @Override
          public io.casehub.api.context.CaseContext awaitCase(UUID childCaseId, Duration timeout) {
            return null;
          }

          @Override
          public io.casehub.api.context.CaseContext spawnAndAwaitCase(
              String caseType, Map<String, Object> input, Duration timeout) {
            return null;
          }
        };
    assertNotNull(runtime.caseId());
  }
}
