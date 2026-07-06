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

import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.ReactiveCaseMetaModelRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class JpaReactiveCaseMetaModelRepository extends TenantAwareRepository
    implements ReactiveCaseMetaModelRepository {

  @Override
  public Uni<CaseMetaModel> findByKey(
      String namespace, String name, String version, String tenancyId) {
    return withTenantTransaction(
        () ->
            CaseMetaModelEntity.<CaseMetaModelEntity>find(
                    "namespace = ?1 and name = ?2 and version = ?3 and tenancyId = ?4",
                    namespace,
                    name,
                    version,
                    tenancyId)
                .firstResult()
                .map(entity -> entity == null ? null : fromEntity(entity)));
  }

  @Override
  public Uni<CaseMetaModel> save(CaseMetaModel metaModel, String tenancyId) {
    CaseMetaModelEntity entity = toEntity(metaModel, tenancyId);
    entity.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    return withTenantTransaction(
        () ->
            entity
                .persist()
                .map(
                    v -> {
                      metaModel.id = entity.id;
                      metaModel.tenancyId = tenancyId;
                      metaModel.setCreatedAt(entity.createdAt);
                      return metaModel;
                    }));
  }

  private CaseMetaModel fromEntity(CaseMetaModelEntity entity) {
    CaseMetaModel m = new CaseMetaModel();
    m.id = entity.id;
    m.tenancyId = entity.tenancyId;
    m.setName(entity.name);
    m.setNamespace(entity.namespace);
    m.setVersion(entity.version);
    m.setTitle(entity.title);
    m.setDsl(entity.dsl);
    m.setDefinition(entity.definition);
    m.setCreatedAt(entity.createdAt);
    return m;
  }

  private CaseMetaModelEntity toEntity(CaseMetaModel m, String tenancyId) {
    CaseMetaModelEntity entity = new CaseMetaModelEntity();
    entity.tenancyId = tenancyId;
    entity.name = m.getName();
    entity.namespace = m.getNamespace();
    entity.version = m.getVersion();
    entity.title = m.getTitle();
    entity.dsl = m.getDsl();
    entity.definition = m.getDefinition();
    return entity;
  }
}
