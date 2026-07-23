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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.SignalTest.SignalCaseHubBean;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that signal() immediately after startCase() does not race — the signal must be delivered
 * reliably even when the case is still initializing.
 *
 * <p>Reproduces casehubio/engine#494: CaseInstance not found in cache immediately after startCase()
 * returns.
 */
@QuarkusTest
class SignalRaceTest {

  @Inject SignalCaseHubBean bean;

  @Inject CaseInstanceCache caseInstanceCache;

  @BeforeEach
  void reset() {
    SignalCaseHubBean.runCount.set(0);
    SignalCaseHubBean.runCountByOrderId.clear();
  }

  /**
   * Core race condition test from engine#494. Start a case and immediately signal — zero delay. The
   * signal must not be lost; the worker must fire exactly once and the case must complete.
   */
  @Test
  void signalImmediatelyAfterStartCaseSucceeds() {
    String orderId = "order-race-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("orderId", orderId));

    // Signal immediately — no Thread.sleep, no await before signal
    bean.signal(caseId, "payment", Map.of("amount", 100, "currency", "EUR"));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              int count =
                  SignalCaseHubBean.runCountByOrderId
                      .getOrDefault(orderId, new java.util.concurrent.atomic.AtomicInteger())
                      .get();
              assertThat(count)
                  .as("Worker must run exactly once after immediate signal")
                  .isEqualTo(1);
              assertThat(caseInstanceCache.get(caseId).getState())
                  .as("Case must reach COMPLETED")
                  .isEqualTo(CaseStatus.COMPLETED);
            });
  }

  /**
   * Confirms that the STARTING→RUNNING transition completes before startCase() returns. The
   * CaseStartedEventHandler runs via eventBus.request() (not publish()), so the CompletionStage
   * resolves only after initialization is done.
   */
  @Test
  void caseIsRunningWhenStartCaseReturns() {
    UUID caseId = bean.startCase(Map.of("orderId", "order-state-check"));

    var instance = caseInstanceCache.get(caseId);
    assertThat(instance).as("CaseInstance must be in cache after startCase() returns").isNotNull();
    assertThat(instance.getState())
        .as("State must be RUNNING (not STARTING) when startCase() returns")
        .isEqualTo(CaseStatus.RUNNING);
  }
}
