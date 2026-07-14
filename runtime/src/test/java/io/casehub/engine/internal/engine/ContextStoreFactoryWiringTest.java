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
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.context.CaseContextStore;
import io.casehub.api.context.CaseContextStoreFactory;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.context.InMemoryCaseContextStoreFactory;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ContextStoreFactoryWiringTest {

  @Inject CaseHubRuntime runtime;
  @Inject CaseInstanceCache caseInstanceCache;

  @BeforeEach
  void reset() {
    RecordingCaseContextStoreFactory.layers.clear();
    RecordingCaseContextStoreFactory.caseIds.clear();
  }

  @Test
  void startCase_withRecordingFactory_usesCorrectFactory() throws Exception {
    RecordingFactoryCaseHub hub = new RecordingFactoryCaseHub();
    UUID caseId =
        runtime.startCase(hub.getDefinition(), Map.of("started", true)).toCompletableFuture().get();

    assertThat(caseId).isNotNull();
    assertThat(RecordingCaseContextStoreFactory.layers)
        .containsExactlyInAnyOrder("working", "semantic", "episodic");
    assertThat(RecordingCaseContextStoreFactory.caseIds).isNotEmpty();
    assertThat(RecordingCaseContextStoreFactory.caseIds.get(0)).isEqualTo(caseId);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.COMPLETED);
            });
  }

  @Test
  void startCase_noFactory_completesSuccessfully() throws Exception {
    DefaultFactoryCaseHub hub = new DefaultFactoryCaseHub();
    UUID caseId =
        runtime
            .startCase(hub.getDefinition(), Map.of("started2", true))
            .toCompletableFuture()
            .get();

    assertThat(caseId).isNotNull();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.COMPLETED);
            });
  }

  @ApplicationScoped
  static class RecordingCaseContextStoreFactory implements CaseContextStoreFactory {
    static final CopyOnWriteArrayList<String> layers = new CopyOnWriteArrayList<>();
    static final CopyOnWriteArrayList<UUID> caseIds = new CopyOnWriteArrayList<>();

    @Override
    public String id() {
      return "recording";
    }

    @Override
    public CaseContextStore createStore(String layerName, UUID caseId) {
      layers.add(layerName);
      if (caseId != null) {
        caseIds.add(caseId);
      }
      return InMemoryCaseContextStoreFactory.INSTANCE.createStore(layerName, caseId);
    }
  }

  @ApplicationScoped
  static class RecordingFactoryCaseHub extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test")
          .name("Recording Factory Test")
          .version("1.0.0")
          .contextStoreFactory("recording")
          .capabilities(
              Capability.builder().name("noop").inputSchema(".").outputSchema(".").build())
          .workers(
              Worker.builder()
                  .name("noop-worker")
                  .capabilityName("noop")
                  .<Map<String, Object>>fn()
                  .apply(input -> WorkerResult.of(Map.of("done", true)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-started")
                  .capability(
                      Capability.builder().name("noop").inputSchema(".").outputSchema(".").build())
                  .on(new ContextChangeTrigger(".started == true"))
                  .build())
          .goals(
              Goal.builder()
                  .name("completed")
                  .condition(".done == true")
                  .kind(GoalKind.SUCCESS)
                  .build())
          .completion(GoalExpression.goal("completed"))
          .build();
    }
  }

  @ApplicationScoped
  static class DefaultFactoryCaseHub extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test")
          .name("Default Factory Test")
          .version("1.0.0")
          .capabilities(
              Capability.builder().name("noop2").inputSchema(".").outputSchema(".").build())
          .workers(
              Worker.builder()
                  .name("noop-worker-2")
                  .capabilityName("noop2")
                  .<Map<String, Object>>fn()
                  .apply(input -> WorkerResult.of(Map.of("done2", true)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-started2")
                  .capability(
                      Capability.builder().name("noop2").inputSchema(".").outputSchema(".").build())
                  .on(new ContextChangeTrigger(".started2 == true"))
                  .build())
          .goals(
              Goal.builder()
                  .name("completed2")
                  .condition(".done2 == true")
                  .kind(GoalKind.SUCCESS)
                  .build())
          .completion(GoalExpression.goal("completed2"))
          .build();
    }
  }
}
