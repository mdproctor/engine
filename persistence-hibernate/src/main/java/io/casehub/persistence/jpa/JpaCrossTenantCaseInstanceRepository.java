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
package io.casehub.persistence.jpa;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;

/**
 * Blocking cross-tenant JPA {@link CrossTenantCaseInstanceRepository}. Direct EntityManager
 * implementation.
 */
@ApplicationScoped
public class JpaCrossTenantCaseInstanceRepository extends TenantAwareRepository
    implements CrossTenantCaseInstanceRepository {

  @Override
  @Transactional
  public CaseInstance findByUuid(UUID caseId) {
    setCrossTenantContext();
    List<CaseInstanceEntity> results =
        em.createQuery(
                "SELECT ci FROM CaseInstanceEntity ci JOIN FETCH ci.caseMetaModel"
                    + " WHERE ci.uuid = :uuid",
                CaseInstanceEntity.class)
            .setParameter("uuid", caseId)
            .getResultList();
    return results.isEmpty() ? null : fromEntity(results.get(0));
  }

  private CaseInstance fromEntity(CaseInstanceEntity entity) {
    CaseInstance instance = new CaseInstance();
    instance.id = entity.id;
    instance.tenancyId = entity.tenancyId;
    instance.setUuid(entity.uuid);
    instance.setState(entity.state);
    instance.setParentCaseId(entity.parentCaseId);
    instance.setParentPlanItemId(entity.parentPlanItemId);
    instance.setWaitingForWorkId(entity.waitingForWorkId);
    if (entity.caseMetaModel != null) {
      CaseMetaModel m = new CaseMetaModel();
      m.id = entity.caseMetaModel.id;
      m.tenancyId = entity.caseMetaModel.tenancyId;
      m.setName(entity.caseMetaModel.name);
      m.setNamespace(entity.caseMetaModel.namespace);
      m.setVersion(entity.caseMetaModel.version);
      m.setTitle(entity.caseMetaModel.title);
      m.setDsl(entity.caseMetaModel.dsl);
      m.setDefinition(entity.caseMetaModel.definition);
      m.setCreatedAt(entity.caseMetaModel.createdAt);
      instance.setCaseMetaModel(m);
    }
    return instance;
  }
}
