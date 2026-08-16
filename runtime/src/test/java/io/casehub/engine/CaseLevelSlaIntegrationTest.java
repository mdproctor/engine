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
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.ScheduleTrigger;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CaseLevelSlaIntegrationTest {

  @Inject SlaTimeoutBean slaBean;
  @Inject CaseInstanceCache caseInstanceCache;

  @BeforeEach
  void setUp() {
    SlaTimeoutBean.workerExecutionCount.set(0);
  }

  @Test
  void signalTarget_writesToContextAfterDelay() {
    UUID caseId = slaBean.startCase(Map.of("started", true));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(200, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              CaseInstance instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              Object slaValue = instance.getCaseContext().get("caseSla");
              assertThat(slaValue).isNotNull();
            });
  }

  @Test
  void signalTarget_triggersFailureGoal() {
    UUID caseId = slaBean.startCase(Map.of("started", true));

    await()
        .atMost(15, TimeUnit.SECONDS)
        .pollInterval(200, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              CaseInstance instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.FAULTED);
            });
  }

  @ApplicationScoped
  static class SlaTimeoutBean extends CaseHub {
    static final AtomicInteger workerExecutionCount = new AtomicInteger(0);

    @Override
    public CaseDefinition getDefinition() {
      Capability reviewCap =
          Capability.builder()
              .name("review-code")
              .inputSchema(".")
              .outputSchema("{ reviewResult: .reviewResult }")
              .build();

      Worker reviewer =
          Worker.builder()
              .name("code-reviewer")
              .capabilityName("review-code")
              .function(
                  new WorkerFunction.Sync<>(
                      Map.class,
                      Map.class,
                      (ctx, scope) -> {
                        workerExecutionCount.incrementAndGet();
                        return WorkerResult.of(Map.of("reviewResult", "approved"));
                      }))
              .build();

      Goal reviewComplete =
          Goal.builder()
              .name("review-complete")
              .condition(new JQExpressionEvaluator(".reviewResult != null"))
              .kind(GoalKind.SUCCESS)
              .build();

      Goal reviewTimedOut =
          Goal.builder()
              .name("review-timed-out")
              .condition(new JQExpressionEvaluator(".caseSla.expired == true"))
              .kind(GoalKind.FAILURE)
              .build();

      Binding doReview =
          Binding.builder()
              .name("do-review")
              .capability(reviewCap)
              .on(new ContextChangeTrigger(".reviewRequest != null"))
              .build();

      Binding caseTimeout =
          Binding.builder()
              .name("case-timeout")
              .signal(Map.of("caseSla", Map.of("expired", true)))
              .on(ScheduleTrigger.delay(Duration.ofSeconds(2)))
              .when(".caseSla.expired == null")
              .build();

      return CaseDefinition.builder()
          .name("sla-integration-test")
          .namespace("test")
          .version("1.0")
          .capabilities(reviewCap)
          .workers(reviewer)
          .bindings(doReview, caseTimeout)
          .goals(reviewComplete, reviewTimedOut)
          .completion(GoalExpression.allOf(reviewComplete), GoalExpression.anyOf(reviewTimedOut))
          .build();
    }
  }
}
