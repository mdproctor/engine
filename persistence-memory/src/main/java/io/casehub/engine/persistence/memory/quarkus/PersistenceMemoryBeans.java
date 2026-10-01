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
package io.casehub.engine.persistence.memory.quarkus;

import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.recovery.CaseContextRecoveryStrategy;
import io.casehub.engine.persistence.memory.DefaultTestPrincipal;
import io.casehub.engine.persistence.memory.InMemoryCaseInstanceRepository;
import io.casehub.engine.persistence.memory.InMemoryCaseMetaModelRepository;
import io.casehub.engine.persistence.memory.InMemoryEventLogRepository;
import io.casehub.engine.persistence.memory.InMemoryPlanItemStore;
import io.casehub.engine.persistence.memory.InMemorySubCaseGroupRepository;
import io.quarkus.arc.DefaultBean;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class PersistenceMemoryBeans {

  @Produces
  @DefaultBean
  @ApplicationScoped
  DefaultTestPrincipal defaultTestPrincipal() {
    return new DefaultTestPrincipal();
  }

  @Produces
  @DefaultBean
  @ApplicationScoped
  InMemoryCaseInstanceRepository inMemoryCaseInstanceRepository(
      EventLogRepository eventLogRepository,
      Instance<CaseContextRecoveryStrategy> recoveryStrategy) {
    InMemoryCaseInstanceRepository repo = new InMemoryCaseInstanceRepository(eventLogRepository);
    if (recoveryStrategy.isResolvable()) {
      repo.setRecoveryStrategy(recoveryStrategy.get());
    }
    return repo;
  }

  @Produces
  @DefaultBean
  @ApplicationScoped
  InMemoryCaseMetaModelRepository inMemoryCaseMetaModelRepository() {
    return new InMemoryCaseMetaModelRepository();
  }

  @Produces
  @DefaultBean
  @ApplicationScoped
  InMemoryEventLogRepository inMemoryEventLogRepository() {
    return new InMemoryEventLogRepository();
  }

  @Produces
  @DefaultBean
  @ApplicationScoped
  InMemorySubCaseGroupRepository inMemorySubCaseGroupRepository() {
    return new InMemorySubCaseGroupRepository();
  }

  @Produces
  @Alternative
  @Priority(100)
  @ApplicationScoped
  InMemoryPlanItemStore inMemoryPlanItemStore() {
    return new InMemoryPlanItemStore();
  }
}
