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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerResult;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class SignalTest {

  @Inject SignalCaseHubBean bean;

  @Inject TwoSignalCaseHubBean twoSignalBean;

  @Inject CaseInstanceCache caseInstanceCache;

  private static final Logger LOG = Logger.getLogger(SignalTest.class);

  @BeforeEach
  void reset() {
    SignalCaseHubBean.runCount.set(0);
    SignalCaseHubBean.runCountByOrderId.clear();
    TwoSignalCaseHubBean.paymentRunCount.set(0);
    TwoSignalCaseHubBean.documentRunCount.set(0);
  }

  // ------------------------------------------------------------------ //

  /**
   * Case starts without the trigger field — worker must NOT run. After signal writes the field, the
   * rule fires and worker executes.
   */
  @Test
  void workerShouldNotRunBeforeSignalAndRunAfter() {
    String orderId = "order-signal-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("orderId", orderId)).toCompletableFuture().join();

    assertNotNull(caseId);

    // worker must NOT run before signal
    await()
        .during(2, TimeUnit.SECONDS)
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertEquals(0, runCountForOrder(orderId), "Worker must not run before signal"));

    // send signal — writes the triggering field
    bean.signal(caseId, "payment", Map.of("amount", 500, "currency", "USD"));

    awaitWorkerCompletedExactlyOnce(caseId, orderId, "Worker must run exactly once after signal");
  }

  /**
   * Worker must run exactly once even if the same signal is sent twice. The dedup mechanism should
   * prevent double execution.
   */
  @Test
  void workerRunsOnceOnDuplicateSignal() {

    SignalCaseHubBean.runCount.set(0);
    SignalCaseHubBean.runCountByOrderId.clear();

    LOG.warnf("Starting signal");

    // Unique orderId per run — isolates this test's run count from other tests' workers
    String orderId = "order-dedup-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("orderId", orderId)).toCompletableFuture().join();

    // Reset after startCase: any async workers from previous tests should have settled
    // by the time startCase() returns (it's a blocking call). This minimises contamination.
    SignalCaseHubBean.runCount.set(0);

    bean.signal(caseId, "payment", Map.of("amount", 100, "currency", "EUR"));
    bean.signal(caseId, "payment", Map.of("amount", 100, "currency", "EUR"));

    awaitWorkerCompletedExactlyOnce(
        caseId, orderId, "Worker must run exactly once despite duplicate signal");
    LOG.warnf("Finished signal");
  }

  /** Signal with a primitive value (not a Map) must also trigger the rule. */
  @Test
  void signalWithPrimitiveValueTriggersWorker() {

    SignalCaseHubBean.runCount.set(0);
    SignalCaseHubBean.runCountByOrderId.clear();

    String orderId = "order-primitive-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("orderId", orderId)).toCompletableFuture().join();

    // signal writes a non-null value — rule checks `.payment != null`
    bean.signal(caseId, "payment", 999);

    awaitWorkerCompletedExactlyOnce(
        caseId, orderId, "Primitive signal must trigger exactly one worker execution");
  }

  /**
   * Two independent signals trigger two different workers. Case completes only after both workers
   * finish.
   */
  @Test
  void twoIndependentSignalsTriggerTwoWorkers() {
    SignalCaseHubBean.runCount.set(0);
    SignalCaseHubBean.runCountByOrderId.clear();
    UUID caseId =
        twoSignalBean
            .startCase(Map.of("orderId", "order-two-signals"))
            .toCompletableFuture()
            .join();

    // neither worker should run yet
    await()
        .during(2, TimeUnit.SECONDS)
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(0, TwoSignalCaseHubBean.paymentRunCount.get());
              assertEquals(0, TwoSignalCaseHubBean.documentRunCount.get());
            });

    twoSignalBean.signal(caseId, "paymentApproved", Map.of("amount", 200));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertEquals(1, TwoSignalCaseHubBean.paymentRunCount.get()));
    assertEquals(
        0, TwoSignalCaseHubBean.documentRunCount.get(), "Document worker must not run yet");

    twoSignalBean.signal(caseId, "documentUploaded", Map.of("filename", "contract.pdf"));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(1, TwoSignalCaseHubBean.paymentRunCount.get());
              assertEquals(1, TwoSignalCaseHubBean.documentRunCount.get());
              assertEquals(CaseStatus.COMPLETED, caseInstanceCache.get(caseId).getState());
            });
  }

  /** Signal written value must be readable through query after the signal is processed. */
  @Test
  void signalValueIsAvailableViaQuery() {
    SignalCaseHubBean.runCount.set(0);
    SignalCaseHubBean.runCountByOrderId.clear();

    String orderId = "order-query-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("orderId", orderId)).toCompletableFuture().join();

    bean.signal(caseId, "payment", Map.of("amount", 750, "currency", "GBP"));

    awaitWorkerCompletedExactlyOnce(caseId, orderId, "Signal-driven worker must complete once");

    // the worker output wrote "status" = "paid" into the context
    String status = bean.query(caseId, "status", String.class).toCompletableFuture().join();
    assertEquals("paid", status);
  }

  private void awaitWorkerCompletedExactlyOnce(UUID caseId, String orderId, String message) {
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(1, runCountForOrder(orderId), message);
              assertEquals(CaseStatus.COMPLETED, caseInstanceCache.get(caseId).getState());
            });

    await()
        .during(1, TimeUnit.SECONDS)
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(() -> assertEquals(1, runCountForOrder(orderId), message));
  }

  private int runCountForOrder(String orderId) {
    return SignalCaseHubBean.runCountByOrderId.getOrDefault(orderId, new AtomicInteger()).get();
  }

  // ================================================================== //
  //  Beans                                                              //
  // ================================================================== //

  /**
   * Case with a single rule triggered by {@code .payment != null}. Starts without {@code payment}
   * in context so no worker fires immediately.
   */
  @ApplicationScoped
  public static class SignalCaseHubBean extends CaseHub {

    static final AtomicInteger runCount = new AtomicInteger();
    static final ConcurrentHashMap<String, AtomicInteger> runCountByOrderId =
        new ConcurrentHashMap<>();

    private final Capability paymentCapability =
        Capability.builder()
            .name("processPayment")
            .inputSchema("{ payment: .working.payment, orderId: .working.orderId }")
            .outputSchema("{ status: .status }")
            .build();

    private final Goal paidGoal =
        Goal.builder()
            .name("paymentProcessed")
            .condition(".working.status == \"paid\"")
            .kind(GoalKind.SUCCESS)
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-signal")
          .name("Signal Test")
          .version("1.0.0")
          .capabilities(paymentCapability)
          .workers(
              Worker.builder()
                  .name("payment-worker")
                  .capabilities(paymentCapability)
                  .function(
                      input -> {
                        LOG.warnf("WORKER INPUT: %s %s", input, runCount.incrementAndGet());

                        String orderId = (String) input.get("orderId");
                        if (orderId != null) {
                          runCountByOrderId
                              .computeIfAbsent(orderId, k -> new AtomicInteger())
                              .incrementAndGet();
                        }
                        return WorkerResult.of(Map.of("status", "paid"));
                      })
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-payment-received")
                  .capability(paymentCapability)
                  .on(new ContextChangeTrigger(".working.payment != null"))
                  .build())
          .goals(paidGoal)
          .completion(GoalExpression.allOf(paidGoal))
          .build();
    }
  }

  /**
   * Case with two independent rules, each triggered by a different signal. Completes only after
   * both workers finish.
   */
  @ApplicationScoped
  public static class TwoSignalCaseHubBean extends CaseHub {

    static final AtomicInteger paymentRunCount = new AtomicInteger();
    static final AtomicInteger documentRunCount = new AtomicInteger();

    private final Capability paymentCapability =
        Capability.builder()
            .name("processPayment2")
            .inputSchema("{ paymentApproved: .working.paymentApproved }")
            .outputSchema("{ paymentProcessed: .paymentProcessed }")
            .build();

    private final Capability documentCapability =
        Capability.builder()
            .name("processDocument2")
            .inputSchema("{ documentUploaded: .working.documentUploaded }")
            .outputSchema("{ documentProcessed: .documentProcessed }")
            .build();

    private final Goal allDoneGoal =
        Goal.builder()
            .name("allDone")
            .condition(".working.paymentProcessed == true and .working.documentProcessed == true")
            .kind(GoalKind.SUCCESS)
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-signal-two")
          .name("Two Signal Test")
          .version("1.0.0")
          .capabilities(paymentCapability, documentCapability)
          .workers(
              Worker.builder()
                  .name("payment-worker-2")
                  .capabilities(paymentCapability)
                  .function(
                      input -> {
                        paymentRunCount.incrementAndGet();
                        return WorkerResult.of(Map.of("paymentProcessed", true));
                      })
                  .build(),
              Worker.builder()
                  .name("document-worker-2")
                  .capabilities(documentCapability)
                  .function(
                      input -> {
                        documentRunCount.incrementAndGet();
                        return WorkerResult.of(Map.of("documentProcessed", true));
                      })
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-payment-approved")
                  .capability(paymentCapability)
                  .on(new ContextChangeTrigger(".working.paymentApproved != null"))
                  .build(),
              Binding.builder()
                  .name("on-document-uploaded")
                  .capability(documentCapability)
                  .on(new ContextChangeTrigger(".working.documentUploaded != null"))
                  .build())
          .goals(allDoneGoal)
          .completion(GoalExpression.allOf(allDoneGoal))
          .build();
    }
  }
}
