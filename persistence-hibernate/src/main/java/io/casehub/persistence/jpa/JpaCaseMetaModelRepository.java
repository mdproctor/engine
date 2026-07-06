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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Blocking JPA {@link CaseMetaModelRepository}. Delegates to {@link
 * JpaReactiveCaseMetaModelRepository} and awaits.
 */
@ApplicationScoped
public class JpaCaseMetaModelRepository implements CaseMetaModelRepository {

  @Inject JpaReactiveCaseMetaModelRepository delegate;

  @Override
  public CaseMetaModel findByKey(String namespace, String name, String version, String tenancyId) {
    return delegate.findByKey(namespace, name, version, tenancyId).await().indefinitely();
  }

  @Override
  public CaseMetaModel save(CaseMetaModel metaModel, String tenancyId) {
    return delegate.save(metaModel, tenancyId).await().indefinitely();
  }
}
