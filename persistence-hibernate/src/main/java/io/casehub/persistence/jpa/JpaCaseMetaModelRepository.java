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
import io.casehub.engine.common.spi.query.CaseDefinitionQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Blocking JPA {@link CaseMetaModelRepository}. Direct EntityManager implementation. */
@ApplicationScoped
public class JpaCaseMetaModelRepository extends TenantAwareRepository
    implements CaseMetaModelRepository {

  @Override
  @Transactional
  public CaseMetaModel findByKey(String namespace, String name, String version, String tenancyId) {
    setTenantContext(tenancyId);
    List<CaseMetaModelEntity> results =
        em.createQuery(
                "SELECT m FROM CaseMetaModelEntity m"
                    + " WHERE m.namespace = :ns AND m.name = :name AND m.version = :ver"
                    + " AND m.tenancyId = :tid",
                CaseMetaModelEntity.class)
            .setParameter("ns", namespace)
            .setParameter("name", name)
            .setParameter("ver", version)
            .setParameter("tid", tenancyId)
            .getResultList();
    return results.isEmpty() ? null : fromEntity(results.get(0));
  }

  @Override
  @Transactional
  public CaseMetaModel save(CaseMetaModel metaModel, String tenancyId) {
    setTenantContext(tenancyId);
    CaseMetaModelEntity entity = toEntity(metaModel, tenancyId);
    entity.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    em.persist(entity);
    em.flush();
    metaModel.id = entity.id;
    metaModel.tenancyId = tenancyId;
    metaModel.setCreatedAt(entity.createdAt);
    return metaModel;
  }

  @Override
  @Transactional
  public List<CaseMetaModel> query(CaseDefinitionQuery query, String tenancyId) {
    setTenantContext(tenancyId);
    StringBuilder hql =
        new StringBuilder("SELECT m FROM CaseMetaModelEntity m WHERE m.tenancyId = :tid");
    java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("tid", tenancyId);
    if (query.namespace() != null) {
      hql.append(" AND m.namespace = :ns");
      params.put("ns", query.namespace());
    }
    if (query.name() != null) {
      hql.append(" AND m.name = :name");
      params.put("name", query.name());
    }
    var typedQuery = em.createQuery(hql.toString(), CaseMetaModelEntity.class);
    params.forEach(typedQuery::setParameter);
    typedQuery.setFirstResult(query.page() * query.size());
    typedQuery.setMaxResults(query.size());
    return typedQuery.getResultList().stream().map(this::fromEntity).toList();
  }

  @Override
  @Transactional
  public long count(CaseDefinitionQuery query, String tenancyId) {
    setTenantContext(tenancyId);
    StringBuilder hql =
        new StringBuilder("SELECT COUNT(m) FROM CaseMetaModelEntity m WHERE m.tenancyId = :tid");
    java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("tid", tenancyId);
    if (query.namespace() != null) {
      hql.append(" AND m.namespace = :ns");
      params.put("ns", query.namespace());
    }
    if (query.name() != null) {
      hql.append(" AND m.name = :name");
      params.put("name", query.name());
    }
    var typedQuery = em.createQuery(hql.toString(), Long.class);
    params.forEach(typedQuery::setParameter);
    return typedQuery.getSingleResult();
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
