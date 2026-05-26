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
import io.smallrye.mutiny.Uni;

/**
 * Storage provider for {@link CaseMetaModel} definitions. Implementations handle their own
 * session/transaction management.
 */
public interface CaseMetaModelRepository {

  /**
   * Find a registered case type by its natural key. Returns {@code null} if not found.
   *
   * @param namespace the case namespace (may be null for unnamespaced definitions)
   * @param name the case name
   * @param version the semantic version string
   */
  Uni<CaseMetaModel> findByKey(String namespace, String name, String version);

  /**
   * Persist a new case meta model. Sets {@code metaModel.id} and {@code metaModel.createdAt} on
   * completion if not already set.
   */
  Uni<CaseMetaModel> save(CaseMetaModel metaModel);
}
