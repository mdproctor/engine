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
package io.casehub.persistence.memory.quarkus;

import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.persistence.memory.DefaultTestPrincipal;
import io.casehub.persistence.memory.InMemoryCaseInstanceRepository;
import io.casehub.persistence.memory.InMemoryCaseMetaModelRepository;
import io.casehub.persistence.memory.InMemoryEventLogRepository;
import io.casehub.persistence.memory.InMemoryPlanItemStore;
import io.casehub.persistence.memory.InMemorySubCaseGroupRepository;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class PersistenceMemoryBeans {

  @Produces
  @DefaultBean
  DefaultTestPrincipal defaultTestPrincipal() {
    return new DefaultTestPrincipal();
  }

  @Produces
  @Alternative
  InMemoryCaseInstanceRepository inMemoryCaseInstanceRepository(
      EventLogRepository eventLogRepository) {
    return new InMemoryCaseInstanceRepository(eventLogRepository);
  }

  @Produces
  @Alternative
  InMemoryCaseMetaModelRepository inMemoryCaseMetaModelRepository() {
    return new InMemoryCaseMetaModelRepository();
  }

  @Produces
  @Alternative
  InMemoryEventLogRepository inMemoryEventLogRepository() {
    return new InMemoryEventLogRepository();
  }

  @Produces
  @Alternative
  InMemoryPlanItemStore inMemoryPlanItemStore() {
    return new InMemoryPlanItemStore();
  }

  @Produces
  @Alternative
  InMemorySubCaseGroupRepository inMemorySubCaseGroupRepository() {
    return new InMemorySubCaseGroupRepository();
  }
}
