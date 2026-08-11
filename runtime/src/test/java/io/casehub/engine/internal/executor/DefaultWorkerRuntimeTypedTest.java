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
package io.casehub.engine.internal.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultWorkerRuntimeTypedTest {

  private static final UUID CASE_ID = UUID.randomUUID();

  public record OrderInput(String product, int quantity) {}

  @Test
  void execute_byName_convertsMapToPojoInput() {
    var fn =
        new WorkerFunction.Sync<>(
            OrderInput.class,
            Map.class,
            (order, scope) -> {
              assertEquals("widget", order.product());
              assertEquals(5, order.quantity());
              return WorkerResult.of(Map.of("total", order.quantity() * 10));
            });

    Worker worker =
        Worker.builder().name("order-worker").capabilityName("processOrder").function(fn).build();

    CaseDefinition definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("typed-test")
            .version("1.0")
            .workers(worker)
            .build();

    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setName("typed-test");
    metaModel.setNamespace("test");
    metaModel.setVersion("1.0");

    CaseInstance instance = new CaseInstance();
    instance.setUuid(CASE_ID);
    instance.setCaseMetaModel(metaModel);

    CaseDefinitionRegistry registry =
        new CaseDefinitionRegistry() {
          @Override
          public CaseMetaModel registerCaseDefinition(CaseDefinition d) {
            return null;
          }

          @Override
          public CaseDefinition getCaseDefinition(CaseMetaModel m) {
            return definition;
          }

          @Override
          public CaseMetaModel getCaseMetaModel(CaseDefinition d) {
            return metaModel;
          }

          @Override
          public Optional<CaseDefinition> findByName(String name) {
            return Optional.of(definition);
          }
        };

    CaseInstanceCache cache =
        new CaseInstanceCache() {
          @Override
          public CaseInstance get(UUID id) {
            return CASE_ID.equals(id) ? instance : null;
          }

          @Override
          public void put(CaseInstance i) {}

          @Override
          public void clear() {}

          @Override
          public java.util.List<CaseInstance> getAll() {
            return java.util.List.of();
          }
        };

    var runtime =
        new DefaultWorkerRuntime(
            CASE_ID, "test-task", null, java.util.Map.of(), null, registry, cache, null);

    WorkerResult<?> result =
        runtime.execute("order-worker", Map.of("product", "widget", "quantity", 5));

    assertInstanceOf(WorkerOutcome.Success.class, result.outcome());
    @SuppressWarnings("unchecked")
    Map<String, Object> output = (Map<String, Object>) result.output();
    assertEquals(50, output.get("total"));
  }

  @Test
  void execute_typed_directPojoPassthrough() {
    var fn =
        new WorkerFunction.Sync<>(
            OrderInput.class,
            Map.class,
            (order, scope) -> WorkerResult.of(Map.of("confirmed", order.product())));

    var runtime =
        new DefaultWorkerRuntime(
            CASE_ID, "test-task", null, java.util.Map.of(), null, null, null, null);

    WorkerResult<?> result = runtime.execute(fn, new OrderInput("gadget", 3));

    assertInstanceOf(WorkerOutcome.Success.class, result.outcome());
    @SuppressWarnings("unchecked")
    Map<String, Object> output = (Map<String, Object>) result.output();
    assertEquals("gadget", output.get("confirmed"));
  }
}
