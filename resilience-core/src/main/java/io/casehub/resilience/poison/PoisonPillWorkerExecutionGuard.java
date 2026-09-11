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
package io.casehub.resilience.poison;

import io.casehub.api.spi.WorkerExecutionGuard;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * {@link WorkerExecutionGuard} that blocks workers quarantined by the {@link PoisonPillDetector}.
 *
 * <p>Active when {@code casehub-resilience} is on the classpath and selected as the CDI alternative
 * (via {@code quarkus.arc.selected-alternatives} in {@code application.properties}).
 */
@Alternative
@Priority(10)
@ApplicationScoped
public class PoisonPillWorkerExecutionGuard implements WorkerExecutionGuard {

  @Inject PoisonPillDetector detector;

  @Override
  public boolean isBlocked(String workerId, UUID caseId) {
    return detector.isQuarantined(workerId);
  }
}
