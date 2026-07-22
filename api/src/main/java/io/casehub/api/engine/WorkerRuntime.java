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
import io.casehub.api.model.WorkerContext;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public interface WorkerRuntime extends io.casehub.worker.api.WorkerScope {

  WorkerContext context();

  UUID spawnCase(String caseType, Map<String, Object> input);

  CaseContext awaitCase(UUID childCaseId, Duration timeout);

  CaseContext spawnAndAwaitCase(String caseType, Map<String, Object> input, Duration timeout);
}
