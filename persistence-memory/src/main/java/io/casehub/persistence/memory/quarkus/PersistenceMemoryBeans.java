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
