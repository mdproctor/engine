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
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jboss.logging.Logger;

public class PendingWorkRegistry {

  private static final Logger LOG = Logger.getLogger(PendingWorkRegistry.class);

  private final CrossTenantEventLogRepository eventLogRepository;
  private final ConcurrentHashMap<String, List<CompletableFuture<WorkResult>>> pending =
      new ConcurrentHashMap<>();
  private final ReentrantLock lock = new ReentrantLock();

  public PendingWorkRegistry(CrossTenantEventLogRepository eventLogRepository) {
    this.eventLogRepository = eventLogRepository;
  }

  public void onStart() {
    try {
      List<String> correlationKeys = eventLogRepository.findSubmittedWorkWithoutCompletion();
      for (String key : correlationKeys) {
        if (!hasPending(key)) {
          register(key);
          LOG.infof(
              "PendingWorkRegistry: re-registered future for recovered correlationKey=%s", key);
        }
      }
    } catch (Exception err) {
      LOG.errorf(err, "Failed to recover pending work futures on startup");
    }
  }

  public CompletableFuture<WorkResult> register(String correlationKey) {
    CompletableFuture<WorkResult> future = new CompletableFuture<>();
    lock.lock();
    try {
      pending.computeIfAbsent(correlationKey, k -> new ArrayList<>()).add(future);
    } finally {
      lock.unlock();
    }
    LOG.debugf("Registered pending future for correlationKey=%s", correlationKey);
    return future;
  }

  public void complete(String correlationKey, WorkResult result) {
    List<CompletableFuture<WorkResult>> futures;
    lock.lock();
    try {
      futures = pending.remove(correlationKey);
    } finally {
      lock.unlock();
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

  public boolean hasPending(String correlationKey) {
    lock.lock();
    try {
      List<CompletableFuture<WorkResult>> futures = pending.get(correlationKey);
      return futures != null && !futures.isEmpty();
    } finally {
      lock.unlock();
    }
  }
}
