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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.WorkerExecutionKeys;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class SignalPersistenceAndDedupTest {

  private static final Duration SPI_TIMEOUT = Duration.ofSeconds(10);

  @Inject SignalPersistenceCaseHubBean bean;

  @Inject WorkerExecutionRecoveryService recoveryService;

  @Inject CaseInstanceCache caseInstanceCache;

  @Inject EventLogRepository eventLogRepository;

  @BeforeEach
  void reset() {
    SignalPersistenceCaseHubBean.runCount.set(0);
  }

  @Test
  void signalEventPayloadUsesWrappedPatchFormat() {
    UUID caseId = bean.startCase(Map.of("orderId", "order-payload")).toCompletableFuture().join();
    Map<String, Object> payment = Map.of("amount", 125, "currency", "USD");

    bean.signal(caseId, "payment", payment);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  1,
                  findWorkerEvents(
                          caseId, CaseHubEventType.WORKER_EXECUTION_COMPLETED, "payment-worker")
                      .size());

              EventLog signalEvent = latestEvent(caseId, CaseHubEventType.SIGNAL_RECEIVED);
              assertNotNull(signalEvent);
              assertNotNull(signalEvent.getPayload());
              assertTrue(signalEvent.getPayload().isObject());
              assertFalse(signalEvent.getPayload().isArray());

              JsonNode patch = signalEvent.getPayload().get("patch");
              assertNotNull(patch);
              assertTrue(patch.isArray());
              assertTrue(patch.size() > 0);
              assertEquals("/payment", patch.get(0).get("path").asText());
            });
  }

  @Test
  void duplicateSignalSchedulesWorkerOnceForSameInput() {
    UUID caseId = bean.startCase(Map.of("orderId", "order-dedup")).toCompletableFuture().join();
    Map<String, Object> payment = Map.of("amount", 100, "currency", "EUR");
    String inputDataHash = inputDataHash(caseId, payment);

    bean.signal(caseId, "payment", payment);
    bean.signal(caseId, "payment", payment);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  1,
                  findWorkerEvents(
                          caseId, CaseHubEventType.WORKER_EXECUTION_COMPLETED, "payment-worker")
                      .size());
              assertEquals(
                  1,
                  countWorkerEvents(
                      caseId, CaseHubEventType.WORKER_SCHEDULED, "payment-worker", inputDataHash));
              assertEquals(
                  1,
                  countWorkerEvents(
                      caseId,
                      CaseHubEventType.WORKER_EXECUTION_COMPLETED,
                      "payment-worker",
                      inputDataHash));
            });
  }

  @Test
  void changedSignalSchedulesWorkerAgainForNewInput() {
    UUID caseId =
        bean.startCase(Map.of("orderId", "order-different-input")).toCompletableFuture().join();
    Map<String, Object> firstPayment = Map.of("amount", 100, "currency", "EUR");
    Map<String, Object> secondPayment = Map.of("amount", 200, "currency", "EUR");
    String firstHash = inputDataHash(caseId, firstPayment);
    String secondHash = inputDataHash(caseId, secondPayment);

    bean.signal(caseId, "payment", firstPayment);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  1,
                  findWorkerEvents(
                          caseId, CaseHubEventType.WORKER_EXECUTION_COMPLETED, "payment-worker")
                      .size());
              assertEquals(
                  1,
                  countWorkerEvents(
                      caseId, CaseHubEventType.WORKER_SCHEDULED, "payment-worker", firstHash));
            });

    bean.signal(caseId, "payment", secondPayment);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  2,
                  findWorkerEvents(
                          caseId, CaseHubEventType.WORKER_EXECUTION_COMPLETED, "payment-worker")
                      .size());
              assertEquals(
                  1,
                  countWorkerEvents(
                      caseId, CaseHubEventType.WORKER_SCHEDULED, "payment-worker", firstHash));
              assertEquals(
                  1,
                  countWorkerEvents(
                      caseId, CaseHubEventType.WORKER_SCHEDULED, "payment-worker", secondHash));
              assertEquals(
                  2,
                  findWorkerEvents(caseId, CaseHubEventType.WORKER_SCHEDULED, "payment-worker")
                      .size());
              assertEquals(
                  Integer.valueOf(200),
                  bean.query(caseId, "lastProcessedAmount", Integer.class)
                      .toCompletableFuture()
                      .join());
            });
  }

  @Test
  void recoveryRestoresSignalStateFromWrappedPatchPayload() {
    UUID caseId = bean.startCase(Map.of("orderId", "order-recovery")).toCompletableFuture().join();
    Map<String, Object> payment = Map.of("amount", 420, "currency", "CAD");
    String inputDataHash = inputDataHash(caseId, payment);

    bean.signal(caseId, "payment", payment);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  1,
                  findWorkerEvents(
                          caseId, CaseHubEventType.WORKER_EXECUTION_COMPLETED, "payment-worker")
                      .size());
              assertNotNull(latestEvent(caseId, CaseHubEventType.SIGNAL_RECEIVED));
              assertEquals(
                  1,
                  countWorkerEvents(
                      caseId,
                      CaseHubEventType.WORKER_EXECUTION_COMPLETED,
                      "payment-worker",
                      inputDataHash));
            });

    caseInstanceCache.clear();

    CaseInstance restored =
        recoveryService.loadOrRestoreCaseInstance(caseId).await().atMost(SPI_TIMEOUT);

    assertNotNull(restored);
    assertEquals(420, ((Number) restored.getCaseContext().getPath("payment.amount")).intValue());
    assertEquals("CAD", restored.getCaseContext().getPathAsString("payment.currency"));
    assertEquals("processed", restored.getCaseContext().getPathAsString("status"));
    assertEquals(420, ((Number) restored.getCaseContext().get("lastProcessedAmount")).intValue());
  }

  private EventLog latestEvent(UUID caseId, CaseHubEventType eventType) {
    List<EventLog> events = findEvents(caseId, eventType);
    return events.isEmpty() ? null : events.get(events.size() - 1);
  }

  private long countWorkerEvents(
      UUID caseId, CaseHubEventType eventType, String workerId, String inputDataHash) {
    return findWorkerEvents(caseId, eventType, workerId).stream()
        .filter(
            eventLog -> {
              JsonNode metadata = eventLog.getMetadata();
              JsonNode existingHash = metadata == null ? null : metadata.get("inputDataHash");
              return existingHash != null && inputDataHash.equals(existingHash.asText());
            })
        .count();
  }

  private List<EventLog> findEvents(UUID caseId, CaseHubEventType eventType) {
    return eventLogRepository
        .findByCaseAndTypes(caseId, List.of(eventType), TenancyConstants.DEFAULT_TENANT_ID)
        .await()
        .atMost(SPI_TIMEOUT);
  }

  private List<EventLog> findWorkerEvents(
      UUID caseId, CaseHubEventType eventType, String workerId) {
    return eventLogRepository
        .findByCaseAndWorkerAndType(caseId, workerId, eventType, TenancyConstants.DEFAULT_TENANT_ID)
        .await()
        .atMost(SPI_TIMEOUT);
  }

  private String inputDataHash(UUID caseId, Map<String, Object> payment) {
    return WorkerExecutionKeys.inputDataHash(
        caseId, "payment-worker", "processPayment", Map.of("payment", payment));
  }

  @ApplicationScoped
  public static class SignalPersistenceCaseHubBean extends CaseHub {

    static final AtomicInteger runCount = new AtomicInteger();

    private final Capability paymentCapability =
        Capability.builder()
            .name("processPayment")
            .inputSchema("{ payment: .payment }")
            .outputSchema("{ status: .status, lastProcessedAmount: .lastProcessedAmount }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-signal-persistence")
          .name("Signal Persistence Test")
          .version("1.0.0")
          .capabilities(paymentCapability)
          .workers(
              Worker.builder()
                  .name("payment-worker")
                  .capabilities(paymentCapability)
                  .function(
                      new WorkerFunction.Sync(
                          input -> {
                            runCount.incrementAndGet();
                            Map<String, Object> payment =
                                (Map<String, Object>) input.get("payment");
                            Number amount = (Number) payment.get("amount");
                            return WorkerResult.of(
                                Map.of(
                                    "status",
                                    "processed",
                                    "lastProcessedAmount",
                                    amount.intValue()));
                          }))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-payment-received")
                  .capability(paymentCapability)
                  .on(new ContextChangeTrigger(".payment != null"))
                  .build())
          .build();
    }
  }
}
