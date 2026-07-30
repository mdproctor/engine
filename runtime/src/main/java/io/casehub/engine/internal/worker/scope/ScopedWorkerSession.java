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
package io.casehub.engine.internal.worker.scope;

import io.casehub.api.model.LifecycleScope;
import io.casehub.api.model.Participation;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

public sealed interface ScopedWorkerSession
    permits ScopedWorkerSession.Persistent, ScopedWorkerSession.Reinvoked {

  String bindingName();

  UUID caseId();

  String planItemId();

  LifecycleScope scope();

  Participation participation();

  record Persistent(
      String bindingName,
      UUID caseId,
      String planItemId,
      LifecycleScope scope,
      Participation participation,
      BlockingQueue<ContextEvent> mailbox,
      Future<?> workerThread)
      implements ScopedWorkerSession {}

  record Reinvoked(
      String bindingName,
      UUID caseId,
      String planItemId,
      LifecycleScope scope,
      Participation participation,
      AtomicReference<Map<String, Object>> accumulatedState)
      implements ScopedWorkerSession {}
}
