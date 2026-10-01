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
package io.casehub.engine.internal.engine.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.context.CaseContextStore;
import io.casehub.api.context.CaseContextStoreFactory;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.engine.internal.context.InMemoryCaseContextStore;
import io.casehub.persistence.memory.InMemoryEventLogRepository;
import io.casehub.platform.api.routing.NamedStrategy;
import io.casehub.platform.api.routing.StrategyResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultWorkerExecutionRecoveryServiceTest {

  private DefaultWorkerExecutionRecoveryService service;
  private final ConcurrentHashMap<UUID, CaseInstance> caseStore = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, CaseInstance> cache = new ConcurrentHashMap<>();
  private final AtomicInteger loadStoreCalls = new AtomicInteger();
  private CaseDefinition durableDefinition;

  @BeforeEach
  void setUp() {
    service = new DefaultWorkerExecutionRecoveryService();

    service.caseInstanceRepository = caseId -> Optional.ofNullable(caseStore.get(caseId));

    service.caseInstanceCache =
        new CaseInstanceCache() {
          @Override
          public void put(CaseInstance instance) {
            cache.put(instance.getUuid(), instance);
          }

          @Override
          public CaseInstance get(UUID caseId) {
            return cache.get(caseId);
          }

          @Override
          public void clear() {
            cache.clear();
          }

          @Override
          public List<CaseInstance> getAll() {
            return List.copyOf(cache.values());
          }
        };

    EventLogReplayRecoveryStrategy fallback =
        new EventLogReplayRecoveryStrategy(new InMemoryEventLogRepository());
    service.recoveryStrategy = new SnapshotRecoveryStrategy(fallback);

    durableDefinition = new CaseDefinition("test", "durable-case", "1.0");
    durableDefinition.setContextStoreFactory("durable-redis");

    CaseDefinitionRegistry registry =
        new CaseDefinitionRegistry() {
          @Override
          public CaseMetaModel registerCaseDefinition(CaseDefinition model) {
            return null;
          }

          @Override
          public CaseDefinition getCaseDefinition(CaseMetaModel definition) {
            if ("durable-case".equals(definition.getName())) {
              return durableDefinition;
            }
            return null;
          }

          @Override
          public CaseMetaModel getCaseMetaModel(CaseDefinition caseDefinition) {
            return null;
          }
        };
    service.caseDefinitionRegistry = registry;

    CaseContextStoreFactory durableFactory =
        new CaseContextStoreFactory() {
          @Override
          public String id() {
            return "durable-redis";
          }

          @Override
          public CaseContextStore createStore(String layerName, UUID caseId) {
            return new InMemoryCaseContextStore();
          }

          @Override
          public CaseContextStore loadStore(String layerName, UUID caseId) {
            loadStoreCalls.incrementAndGet();
            InMemoryCaseContextStore store = new InMemoryCaseContextStore();
            store.put("recovered-from", "durable-store");
            return store;
          }

          @Override
          public boolean isDurable() {
            return true;
          }
        };

    service.strategyResolver =
        new StrategyResolver() {
          @Override
          @SuppressWarnings("unchecked")
          public <T extends NamedStrategy> T resolve(Class<T> type, String id) {
            if ("durable-redis".equals(id)) {
              return (T) durableFactory;
            }
            throw new IllegalArgumentException("Unknown strategy: " + id);
          }

          @Override
          public <T extends NamedStrategy> Optional<T> find(Class<T> type, String id) {
            return Optional.empty();
          }

          @Override
          public <T extends NamedStrategy> T defaultStrategy(Class<T> type) {
            return null;
          }

          @Override
          public <T extends NamedStrategy> List<T> available(Class<T> type) {
            return List.of();
          }
        };
  }

  @Test
  void durableFactoryUsesLoadFromStore() {
    CaseInstance instance = new CaseInstance();
    UUID caseId = UUID.randomUUID();
    instance.setUuid(caseId);
    instance.setState(CaseStatus.RUNNING);
    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setName("durable-case");
    instance.setCaseMetaModel(metaModel);
    caseStore.put(caseId, instance);

    CaseInstance recovered = service.loadOrRestoreCaseInstance(caseId);

    assertTrue(loadStoreCalls.get() >= 3, "loadStore should be called for built-in layers");
    assertNotNull(recovered.getCaseContext());
    assertEquals("durable-store", recovered.getCaseContext().get("recovered-from"));
  }

  @Test
  void volatileFactoryUsesRecoveryStrategy() {
    CaseInstance instance = new CaseInstance();
    UUID caseId = UUID.randomUUID();
    instance.setUuid(caseId);
    instance.setState(CaseStatus.RUNNING);
    caseStore.put(caseId, instance);

    CaseInstance recovered = service.loadOrRestoreCaseInstance(caseId);

    assertEquals(0, loadStoreCalls.get());
    assertNotNull(recovered.getCaseContext());
  }

  @Test
  void nullMetaModelFallsBackToRecoveryStrategy() {
    CaseInstance instance = new CaseInstance();
    UUID caseId = UUID.randomUUID();
    instance.setUuid(caseId);
    instance.setState(CaseStatus.RUNNING);
    instance.setCaseMetaModel(null);
    caseStore.put(caseId, instance);

    CaseInstance recovered = service.loadOrRestoreCaseInstance(caseId);

    assertEquals(0, loadStoreCalls.get());
    assertNotNull(recovered.getCaseContext());
  }

  @Test
  void unregisteredDefinitionFallsBackToRecoveryStrategy() {
    CaseInstance instance = new CaseInstance();
    UUID caseId = UUID.randomUUID();
    instance.setUuid(caseId);
    instance.setState(CaseStatus.RUNNING);
    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setName("unknown-case");
    instance.setCaseMetaModel(metaModel);
    caseStore.put(caseId, instance);

    CaseInstance recovered = service.loadOrRestoreCaseInstance(caseId);

    assertEquals(0, loadStoreCalls.get());
    assertNotNull(recovered.getCaseContext());
  }

  @Test
  void cachedInstanceReturnedWithoutRecovery() {
    CaseInstance instance = new CaseInstance();
    UUID caseId = UUID.randomUUID();
    instance.setUuid(caseId);
    CaseContextImpl ctx = new CaseContextImpl();
    ctx.set("cached", true);
    instance.setCaseContext(ctx);
    cache.put(caseId, instance);

    CaseInstance recovered = service.loadOrRestoreCaseInstance(caseId);

    assertEquals(true, recovered.getCaseContext().get("cached"));
    assertEquals(0, loadStoreCalls.get());
  }
}
