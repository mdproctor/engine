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

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.OutcomeAction;
import io.casehub.api.model.OutcomePolicy;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the full timeout enrichment path: Worker with null timeoutMs is enriched by the
 * orchestrator to the platform default (2000ms in tests), and the handler enforces it.
 *
 * <p>Covers: orchestrator → WorkerExecutionConfig.withEffectiveTimeout() → handler → timeout.
 *
 * <p>Refs casehubio/engine#1341.
 */
@QuarkusTest
class TimeoutEnrichmentTest {

  @Inject NullTimeoutSlowWorkerBean nullTimeoutSlowWorkerBean;

  @Inject CaseInstanceCache caseInstanceCache;

  @BeforeEach
  void setUp() {
    NullTimeoutSlowWorkerBean.executionCount.set(0);
  }

  @Test
  void nullTimeout_enrichedToDefault_enforcesTimeout() {
    UUID caseId = nullTimeoutSlowWorkerBean.startCase(Map.of("input", "data"));

    await()
        .atMost(3, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> assertThat(NullTimeoutSlowWorkerBean.executionCount.get()).isEqualTo(1));

    // Default timeout is 2000ms (casehub.engine.worker.default-timeout-ms in test config).
    // Worker sleeps 10s, so it must be timed out by the enriched default.
    // OutcomePolicy.onExpired=FAULT causes the case to fault.
    await()
        .atMost(8, TimeUnit.SECONDS)
        .pollInterval(200, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance.getState()).isEqualTo(CaseStatus.FAULTED);
            });
  }

  @ApplicationScoped
  static class NullTimeoutSlowWorkerBean extends CaseHub {
    static final AtomicInteger executionCount = new AtomicInteger(0);

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("enrichmentWork")
              .inputSchema("{ }")
              .outputSchema("{ result: .result }")
              .build();

      Worker worker =
          Worker.builder()
              .name("null-timeout-slow-worker")
              .capabilityName("enrichmentWork")
              .executionPolicy(new ExecutionPolicy(null, null, null))
              .function(
                  new WorkerFunction.Sync<>(
                      Map.class,
                      Map.class,
                      (ctx, scope) -> {
                        executionCount.incrementAndGet();
                        try {
                          Thread.sleep(10000);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return WorkerResult.of(Map.of("result", "should-not-complete"));
                      }))
              .build();

      return CaseDefinition.builder()
          .name("null-timeout-enrichment-test")
          .namespace("test")
          .version("1.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(
              Binding.builder()
                  .name("trigger-null-timeout")
                  .capability(cap)
                  .outcomePolicy(
                      new OutcomePolicy(
                          OutcomeAction.REROUTE, OutcomeAction.REROUTE, OutcomeAction.FAULT, 1))
                  .on(new ContextChangeTrigger(".input != null"))
                  .build())
          .build();
    }
  }
}
