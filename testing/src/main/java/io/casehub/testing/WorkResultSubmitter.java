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
package io.casehub.testing;

import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.worker.api.Worker;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;

/**
 * Test helper that simulates a provisioned worker completing its work.
 *
 * <p>Use this when the case uses a {@link io.casehub.api.spi.WorkerProvisioner} that provisions
 * external workers — inject this bean in your {@code @QuarkusTest} and call {@link #complete(UUID,
 * String, Map)} to drive the engine forward without a real worker process.
 *
 * <pre>{@code
 * workResultSubmitter.complete(caseId, "my-worker", Map.of("result", "done"))
 *     .await().atMost(Duration.ofSeconds(5));
 * }</pre>
 */
@ApplicationScoped
public class WorkResultSubmitter {

  @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;

  @Inject EventBus eventBus;

  public void complete(UUID caseId, String workerId, Map<String, Object> output) {
    CaseInstance instance = caseInstanceRepository.findByUuid(caseId);
    var definition = caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    Worker worker =
        definition.getWorkers().stream()
            .filter(w -> w.name().equals(workerId))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Worker not found in case definition: " + workerId));
    String idempotency = UUID.randomUUID().toString();
    eventBus.publish(
        EventBusAddresses.WORKER_EXECUTION_FINISHED,
        WorkflowExecutionCompleted.approved(instance, worker, idempotency, output, null));
  }
}
