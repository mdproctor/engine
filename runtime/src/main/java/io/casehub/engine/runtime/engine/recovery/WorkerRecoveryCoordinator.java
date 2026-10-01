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
package io.casehub.engine.internal.engine.recovery;

import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.platform.api.acl.WorkerCredentialStore;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkerRecoveryCoordinator {

  private static final Logger LOG = Logger.getLogger(WorkerRecoveryCoordinator.class);

  private final WorkerExecutionRecoveryService recoveryService;
  private final WorkerCredentialStore credentialStore;
  private final Duration recoveryTimeout;
  private volatile RecoveryStatus status = RecoveryStatus.PENDING;

  @Inject
  public WorkerRecoveryCoordinator(
      WorkerExecutionRecoveryService recoveryService,
      WorkerCredentialStore credentialStore,
      @ConfigProperty(name = "casehub.engine.recovery.timeout", defaultValue = "60s")
          Duration recoveryTimeout) {
    this.recoveryService = recoveryService;
    this.credentialStore = credentialStore;
    this.recoveryTimeout = recoveryTimeout;
  }

  void onStart(@Observes @Priority(22) StartupEvent ev) {
    triggerRecovery();
  }

  void triggerRecovery() {
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var future = executor.submit(() -> recoveryService.recoverPendingScheduledWorkers());
      future.get(recoveryTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      status = RecoveryStatus.COMPLETED;
      LOG.info("Worker execution recovery completed");
    } catch (java.util.concurrent.TimeoutException t) {
      status = RecoveryStatus.FAILED;
      LOG.errorf("Worker execution recovery timed out after %s", recoveryTimeout);
    } catch (Exception t) {
      status = RecoveryStatus.FAILED;
      LOG.errorf(t, "Worker execution recovery failed");
    }
  }

  public RecoveryStatus getRecoveryStatus() {
    return status;
  }
}
