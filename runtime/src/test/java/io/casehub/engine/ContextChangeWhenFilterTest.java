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
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
// ContextChangeTrigger is imported via FQN at call sites to avoid ambiguity with schema model
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.Worker;
import io.casehub.engine.spi.cache.CaseInstanceCache;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the binding-level {@code when} field is evaluated for {@code contextChange}
 * triggers. Previously, {@code when} was silently ignored for contextChange bindings — only the
 * {@code on.contextChange.filter} was checked. Refs engine#335.
 */
@QuarkusTest
class ContextChangeWhenFilterTest {

  @Inject CaseInstanceCache cache;
  @Inject WhenFilterCase whenFilterCase;

  @BeforeEach
  void reset() {
    cache.clear();
    WhenFilterCase.guardedWorkerCount.set(0);
    WhenFilterCase.ungardedWorkerCount.set(0);
  }

  @Test
  void contextChange_whenConditionFalse_guardedBindingSkipped() throws Exception {
    // flag=false → binding with when=".flag==true" must not fire
    AtomicReference<UUID> ref = new AtomicReference<>();
    whenFilterCase.startCase(Map.of("flag", false)).thenAccept(ref::set);

    await().atMost(5, TimeUnit.SECONDS).until(() -> ref.get() != null);
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(cache.get(ref.get()).getState()).isEqualTo(CaseStatus.COMPLETED));

    assertThat(WhenFilterCase.guardedWorkerCount.get())
        .as("guarded binding must not fire when 'when' condition is false")
        .isZero();
    assertThat(WhenFilterCase.ungardedWorkerCount.get())
        .as("unguarded binding must fire regardless")
        .isEqualTo(1);
  }

  @Test
  void contextChange_whenConditionTrue_guardedBindingFires() throws Exception {
    // flag=true → both bindings must fire
    AtomicReference<UUID> ref = new AtomicReference<>();
    whenFilterCase.startCase(Map.of("flag", true)).thenAccept(ref::set);

    await().atMost(5, TimeUnit.SECONDS).until(() -> ref.get() != null);
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(cache.get(ref.get()).getState()).isEqualTo(CaseStatus.COMPLETED));

    assertThat(WhenFilterCase.guardedWorkerCount.get())
        .as("guarded binding must fire when 'when' condition is true")
        .isEqualTo(1);
    assertThat(WhenFilterCase.ungardedWorkerCount.get()).isEqualTo(1);
  }

  /**
   * Case with two contextChange bindings:
   *
   * <ul>
   *   <li>{@code guarded-work} — has {@code when: ".flag == true"}; must only fire when flag is
   *       true
   *   <li>{@code finish} — no {@code when}; always fires and drives the case to completion
   * </ul>
   */
  @ApplicationScoped
  public static class WhenFilterCase extends CaseHub {

    static final AtomicInteger guardedWorkerCount = new AtomicInteger(0);
    static final AtomicInteger ungardedWorkerCount = new AtomicInteger(0);

    private final Capability guardedCap =
        Capability.builder()
            .name("guarded-work")
            .inputSchema("{ flag: .flag }")
            .outputSchema("{ guardedRan: true }")
            .build();

    private final Capability finishCap =
        Capability.builder()
            .name("finish")
            .inputSchema("{ flag: .flag }")
            .outputSchema("{ done: true }")
            .build();

    private final Goal goal =
        Goal.builder().name("done").condition(".done == true").kind(GoalKind.SUCCESS).build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-when-filter")
          .name("When Filter Case")
          .version("1.0.0")
          .capabilities(guardedCap, finishCap)
          .workers(
              Worker.builder()
                  .name("guarded-worker")
                  .capabilities(guardedCap)
                  .function(
                      input -> {
                        guardedWorkerCount.incrementAndGet();
                        return Map.of("guardedRan", true);
                      })
                  .build(),
              Worker.builder()
                  .name("finish-worker")
                  .capabilities(finishCap)
                  .function(
                      input -> {
                        ungardedWorkerCount.incrementAndGet();
                        return Map.of("done", true);
                      })
                  .build())
          .bindings(
              Binding.builder()
                  .name("guarded-work")
                  .capability(guardedCap)
                  .on(
                      new io.casehub.api.model.ContextChangeTrigger(
                          (io.casehub.api.model.evaluator.ExpressionEvaluator) null))
                  .when(".flag == true")
                  .build(),
              Binding.builder()
                  .name("finish")
                  .capability(finishCap)
                  .on(
                      new io.casehub.api.model.ContextChangeTrigger(
                          (io.casehub.api.model.evaluator.ExpressionEvaluator) null))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }
}
