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
package io.casehub.resilience.timeout;

import io.casehub.api.model.WorkerResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.BackoffStrategy;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ExecutionPolicy;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.RetryPolicy;
import io.casehub.api.model.Worker;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link CaseTimeoutEnforcer}.
 *
 * <p>Runs with {@code casehub.resilience.timeout.max-duration=PT1S} (set in test
 * application.properties) so the PropagationContext budget expires in 1 second. The scheduler scans
 * every 1 second by default, so hanging cases reach FAULTED within ~2 seconds.
 *
 * <p>Existing integration tests in this module complete well under 1 second (retry exhaustion in
 * ~150ms) and are unaffected by the short budget.
 */
@QuarkusTest
class CaseTimeoutEnforcerIntegrationTest {

  @Inject CaseInstanceCache caseInstanceCache;
  @Inject HangingCaseHub hangingCase;

  @BeforeEach
  void clearCache() {
    caseInstanceCache.clear();
  }

  // ---- Wiring correctness ---------------------------------------------------

  /**
   * Verifies that {@code CaseHubReactor} sets a {@link io.casehub.api.context.PropagationContext}
   * with a deadline on every new {@link CaseInstance}. The deadline comes from {@code
   * casehub.resilience.timeout.max-duration} (PT1S in test config).
   */
  @Test
  void startedCase_hasPropagationContextWithBudget() throws Exception {
    UUID caseId = startCase();

    CaseInstance instance = caseInstanceCache.get(caseId);
    assertThat(instance).isNotNull();
    assertThat(instance.getPropagationContext()).isNotNull();
    assertThat(instance.getPropagationContext().getDeadline()).isPresent();
    assertThat(instance.getPropagationContext().getRemainingBudget()).isPresent();
  }

  // ---- End-to-end timeout ---------------------------------------------------

  /**
   * End-to-end: a case whose worker blocks indefinitely is faulted by the {@link
   * CaseTimeoutEnforcer} once the PropagationContext budget (1s) is exhausted. The scheduler
   * detects the exhausted budget within the next scan cycle and transitions the case to FAULTED.
   */
  @Test
  void hangingCase_budgetExhausted_isTransitionedToFaulted() throws Exception {
    UUID caseId = startCase();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              CaseInstance instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.FAULTED);
            });
  }

  // ---- Helper ---------------------------------------------------------------

  private UUID startCase() throws Exception {
    AtomicReference<UUID> caseIdRef = new AtomicReference<>();
    AtomicReference<Throwable> err = new AtomicReference<>();

    hangingCase
        .startCase(Map.of("trigger", "go"))
        .thenAccept(caseIdRef::set)
        .exceptionally(
            ex -> {
              err.set(ex);
              return null;
            });

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              if (err.get() != null) throw new AssertionError(err.get());
              assertThat(caseIdRef.get()).isNotNull();
            });

    return caseIdRef.get();
  }

  // ---- Case bean ------------------------------------------------------------

  @ApplicationScoped
  public static class HangingCaseHub extends CaseHub {

    private final Capability capability =
        Capability.builder()
            .name("hang")
            .inputSchema("{ trigger: .trigger }")
            .outputSchema("{ trigger: .trigger }")
            .build();

    private final Goal goal =
        Goal.builder()
            .name("done")
            .condition(".trigger == \"done\"")
            .kind(GoalKind.SUCCESS)
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-timeout-it")
          .name("Hanging Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("hanging-worker")
                  .capabilities(capability)
                  .function(
                      input -> {
                        try {
                          Thread.sleep(60_000); // 60s — far exceeds the 1s budget
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return WorkerResult.of(Map.of());
                      })
                  .executionPolicy(
                      // 30s per-execution timeout, 1 retry — budget expires at 1s, long before
                      // either execution timeout or retry kicks in.
                      new ExecutionPolicy(30_000, new RetryPolicy(1, 100, BackoffStrategy.FIXED)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("hang-trigger")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".trigger == \"go\""))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }
}
