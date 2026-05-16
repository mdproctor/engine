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
package io.casehub.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.Worker;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.internal.history.EventLog;
import io.casehub.engine.internal.utils.WorkerExecutionKeys;
import io.casehub.engine.spi.EventLogRepository;
import io.casehub.engine.spi.cache.CaseInstanceCache;
import io.casehub.engine.spi.recovery.WorkerExecutionRecoveryService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class WorkerRecoveryTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Duration SPI_TIMEOUT = Duration.ofSeconds(10);

  @Inject RecoveryCaseHubBean bean;

  @Inject WorkerExecutionRecoveryService recoveryService;

  @Inject CaseInstanceCache caseInstanceCache;

  @Inject EventLogRepository eventLogRepository;

  @Test
  void shouldRecoverScheduledWorkerWhenCacheIsEmpty() {
    RecoveryCaseHubBean.runCount.set(0);

    UUID caseId =
        bean.startCase(Map.of("documentId", "doc-recovery", "status", "scheduled"))
            .toCompletableFuture()
            .join();

    EventLog scheduledEvent = new EventLog();
    scheduledEvent.setCaseId(caseId);
    scheduledEvent.setEventType(CaseHubEventType.WORKER_SCHEDULED);
    scheduledEvent.setStreamType(EventStreamType.CASE);
    scheduledEvent.setTimestamp(Instant.now());
    scheduledEvent.setWorkerId("recovery-worker");
    scheduledEvent.setMetadata(
        OBJECT_MAPPER.valueToTree(
            Map.of(
                "workerName",
                "recovery-worker",
                "capabilityName",
                "recoverCapability",
                "inputDataHash",
                WorkerExecutionKeys.inputDataHash(
                    caseId,
                    "recovery-worker",
                    "recoverCapability",
                    Map.of("documentId", "doc-recovery", "status", "scheduled")))));
    scheduledEvent.setPayload(
        OBJECT_MAPPER.valueToTree(
            Map.of(
                "documentId", "doc-recovery",
                "status", "scheduled")));

    eventLogRepository.append(scheduledEvent).await().atMost(SPI_TIMEOUT);

    caseInstanceCache.clear();

    recoveryService.recoverPendingScheduledWorkers().await().atMost(SPI_TIMEOUT);

    Awaitility.await()
        .atMost(20, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(1, RecoveryCaseHubBean.runCount.get());
              assertEquals(
                  "processed",
                  bean.query(caseId, "status", String.class).toCompletableFuture().join());
            });
  }

  @ApplicationScoped
  public static class RecoveryCaseHubBean extends CaseHub {

    static final AtomicInteger runCount = new AtomicInteger();

    private final Capability capability =
        Capability.builder()
            .name("recoverCapability")
            .inputSchema("{ documentId: .documentId, status: .status }")
            .outputSchema("{ status: .status, processedDocument: .processedDocument }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-worker-recovery")
          .name("Worker Recovery Test")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("recovery-worker")
                  .capabilities(capability)
                  .function(
                      input -> {
                        runCount.incrementAndGet();
                        return Map.of(
                            "status",
                            "processed",
                            "processedDocument",
                            Map.of("id", input.get("documentId")));
                      })
                  .build())
          .build();
    }
  }
}
