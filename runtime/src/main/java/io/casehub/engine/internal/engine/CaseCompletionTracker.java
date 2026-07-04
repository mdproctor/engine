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
package io.casehub.engine.internal.engine;

import io.casehub.api.context.CaseContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks in-flight child case completions for {@link
 * io.casehub.api.engine.WorkerRuntime#awaitCase(UUID, java.time.Duration)}.
 *
 * <p>When a parent worker spawns a child case and awaits its result, the parent registers the child
 * case ID here. When the child case reaches a terminal state, {@link
 * io.casehub.engine.internal.engine.handler.CaseStatusChangedHandler} completes the future with the
 * terminal context snapshot.
 */
@ApplicationScoped
public class CaseCompletionTracker {

  private final ConcurrentHashMap<UUID, CompletableFuture<CaseContext>> pending =
      new ConcurrentHashMap<>();

  public CompletableFuture<CaseContext> register(UUID caseId) {
    return pending.computeIfAbsent(caseId, k -> new CompletableFuture<>());
  }

  public void complete(UUID caseId, CaseContext context) {
    CompletableFuture<CaseContext> future = pending.get(caseId);
    if (future != null) {
      future.complete(context);
    }
  }

  public void completeExceptionally(UUID caseId, Throwable t) {
    CompletableFuture<CaseContext> future = pending.get(caseId);
    if (future != null) {
      future.completeExceptionally(t);
    }
  }

  public void remove(UUID caseId) {
    pending.remove(caseId);
  }
}
