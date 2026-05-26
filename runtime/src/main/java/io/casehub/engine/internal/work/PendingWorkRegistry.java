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
package io.casehub.engine.internal.work;

import io.casehub.api.model.WorkResult;
import io.casehub.engine.common.spi.EventLogRepository;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * In-memory registry of pending {@link CompletableFuture}s for orchestrated work, keyed by
 * idempotency hash (correlationKey). Futures are completed when {@code
 * WorkflowExecutionCompletedHandler} sees the matching {@code WorkflowExecutionCompleted} event.
 *
 * <p>After a JVM restart the in-memory futures are gone. {@code WorkOrchestrator} re-registers
 * futures for any WORK_SUBMITTED EventLog entries without a WORK_COMPLETED entry on startup, so
 * in-flight work recovered by {@code WorkerExecutionRecoveryService} will still resolve the correct
 * future when it completes.
 */
@ApplicationScoped
public class PendingWorkRegistry {

  private static final Logger LOG = Logger.getLogger(PendingWorkRegistry.class);

  @Inject EventLogRepository eventLogRepository;

  private final ConcurrentHashMap<String, List<CompletableFuture<WorkResult>>> pending =
      new ConcurrentHashMap<>();

  void onStart(@Observes @Priority(30) StartupEvent ev) {
    eventLogRepository
        .findSubmittedWorkWithoutCompletion()
        .subscribe()
        .with(
            correlationKeys -> {
              for (String key : correlationKeys) {
                if (!hasPending(key)) {
                  register(key);
                  LOG.infof(
                      "PendingWorkRegistry: re-registered future for recovered correlationKey=%s",
                      key);
                }
              }
            },
            err -> LOG.errorf(err, "Failed to recover pending work futures on startup"));
  }

  /**
   * Registers a new future for the given correlationKey. Multiple futures per key are supported
   * (e.g. two callers both waiting for the same work item).
   */
  public CompletableFuture<WorkResult> register(String correlationKey) {
    CompletableFuture<WorkResult> future = new CompletableFuture<>();
    synchronized (pending) {
      pending.computeIfAbsent(correlationKey, k -> new ArrayList<>()).add(future);
    }
    LOG.debugf("Registered pending future for correlationKey=%s", correlationKey);
    return future;
  }

  /**
   * Completes all futures registered under {@code correlationKey} with the given result and removes
   * the entry. No-op if no future is registered.
   *
   * <p>Futures are completed outside the synchronized block to avoid deadlock if completion
   * callbacks attempt to register new futures.
   */
  public void complete(String correlationKey, WorkResult result) {
    List<CompletableFuture<WorkResult>> futures;
    synchronized (pending) {
      futures = pending.remove(correlationKey);
    }
    if (futures == null) {
      return;
    }
    LOG.debugf(
        "Completing %d future(s) for correlationKey=%s status=%s",
        futures.size(), correlationKey, result.status());
    for (CompletableFuture<WorkResult> future : futures) {
      future.complete(result);
    }
  }

  /** Returns true if at least one future is registered for the given key. */
  public boolean hasPending(String correlationKey) {
    synchronized (pending) {
      List<CompletableFuture<WorkResult>> futures = pending.get(correlationKey);
      return futures != null && !futures.isEmpty();
    }
  }
}
