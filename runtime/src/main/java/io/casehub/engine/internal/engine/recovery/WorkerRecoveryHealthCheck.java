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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

@Liveness
@ApplicationScoped
public class WorkerRecoveryHealthCheck implements HealthCheck {

  private final WorkerRecoveryCoordinator coordinator;

  @Inject
  public WorkerRecoveryHealthCheck(WorkerRecoveryCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  @Override
  public HealthCheckResponse call() {
    return switch (coordinator.getRecoveryStatus()) {
      case PENDING ->
          HealthCheckResponse.named("worker-recovery")
              .up()
              .withData("status", "in-progress")
              .build();
      case COMPLETED -> HealthCheckResponse.named("worker-recovery").up().build();
      case FAILED ->
          HealthCheckResponse.named("worker-recovery").down().withData("status", "failed").build();
    };
  }
}
