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
package io.casehub.engine.common.spi;

import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.query.CaseDefinitionQuery;
import java.util.List;

/**
 * Blocking SPI for {@link CaseMetaModel} definition persistence.
 *
 * <p>tenancyId is explicit — case definitions are per-tenant.
 *
 * @see ReactiveCaseMetaModelRepository
 */
public interface CaseMetaModelRepository {

  /**
   * Find a registered case type by its natural key within the given tenant. Returns {@code null} if
   * not found.
   */
  CaseMetaModel findByKey(String namespace, String name, String version, String tenancyId);

  /**
   * Persist a new case meta model scoped to tenancyId. Sets {@code metaModel.id} and {@code
   * metaModel.createdAt} on completion.
   */
  CaseMetaModel save(CaseMetaModel metaModel, String tenancyId);

  default List<CaseMetaModel> query(CaseDefinitionQuery query, String tenancyId) {
    return List.of();
  }

  default long count(CaseDefinitionQuery query, String tenancyId) {
    return 0;
  }
}
