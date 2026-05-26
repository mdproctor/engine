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
import io.casehub.engine.common.spi.CaseMetaModelRepository;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class JpaCaseMetaModelRepository extends AbstractJpaRepository
    implements CaseMetaModelRepository {

  @Override
  public Uni<CaseMetaModel> findByKey(String namespace, String name, String version) {
    return withSafeContext(
        () ->
            Panache.withSession(
                    () ->
                        CaseMetaModelEntity.<CaseMetaModelEntity>find(
                                "namespace = ?1 and name = ?2 and version = ?3",
                                namespace,
                                name,
                                version)
                            .firstResult())
                .map(entity -> entity == null ? null : fromEntity(entity)));
  }

  @Override
  public Uni<CaseMetaModel> save(CaseMetaModel metaModel) {
    CaseMetaModelEntity entity = toEntity(metaModel);
    entity.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    return withSafeContext(
        () ->
            Panache.withTransaction(() -> entity.persist())
                .map(
                    v -> {
                      metaModel.setId(entity.id);
                      metaModel.setCreatedAt(entity.createdAt);
                      return metaModel;
                    }));
  }

  private CaseMetaModel fromEntity(CaseMetaModelEntity entity) {
    CaseMetaModel m = new CaseMetaModel();
    m.setId(entity.id);
    m.setName(entity.name);
    m.setNamespace(entity.namespace);
    m.setVersion(entity.version);
    m.setTitle(entity.title);
    m.setDsl(entity.dsl);
    m.setDefinition(entity.definition);
    m.setCreatedAt(entity.createdAt);
    return m;
  }

  private CaseMetaModelEntity toEntity(CaseMetaModel m) {
    CaseMetaModelEntity entity = new CaseMetaModelEntity();
    entity.name = m.getName();
    entity.namespace = m.getNamespace();
    entity.version = m.getVersion();
    entity.title = m.getTitle();
    entity.dsl = m.getDsl();
    entity.definition = m.getDefinition();
    return entity;
  }
}
