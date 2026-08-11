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

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ExchangeIntegrationTest {

  @Inject CaseHubRuntime runtime;
  @Inject CaseInstanceCache cache;
  @Inject ExchangeEnricherCaseHub exchangeEnricherBean;

  @Test
  void exchangeProcessor_projectsBodyToContext_andMergesHeaders() {
    UUID caseId = exchangeEnricherBean.startCase(Map.of("trigger", "go"));

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(cache.get(caseId).getState()).isEqualTo(CaseStatus.COMPLETED));

    CaseInstance instance = cache.get(caseId);
    assertThat(instance.getCaseContext().get("enriched")).isEqualTo(true);
    assertThat(instance.getExchangeHeaders()).containsEntry("processedBy", "enricher");
  }

  @ApplicationScoped
  public static class ExchangeEnricherCaseHub extends io.casehub.api.engine.CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder().name("enrichment").inputSchema(".").outputSchema(".").build();

      Worker worker =
          Worker.builder()
              .name("enricher")
              .capabilityName("enrichment")
              .exchange(
                  (exchange, scope) ->
                      WorkerResult.of(
                          exchange
                              .withBody(Map.<String, Object>of("enriched", true))
                              .withHeader("processedBy", "enricher")))
              .build();

      Goal done =
          Goal.builder()
              .name("enriched")
              .condition(".enriched == true")
              .kind(io.casehub.api.model.StandardGoalKind.SUCCESS)
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("ExchangeEnricher")
          .version("1.0.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(
              Binding.builder()
                  .name("enrich-binding")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".trigger != null"))
                  .build())
          .goals(done)
          .completion(GoalExpression.allOf(done))
          .build();
    }
  }
}
