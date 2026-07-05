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
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * Blocking cross-tenant JPA {@link CrossTenantCaseInstanceRepository}. Delegates to {@link
 * JpaReactiveCrossTenantCaseInstanceRepository} and awaits.
 */
@ApplicationScoped
public class JpaCrossTenantCaseInstanceRepository implements CrossTenantCaseInstanceRepository {

  @Inject JpaReactiveCrossTenantCaseInstanceRepository delegate;

  @Override
  public CaseInstance findByUuid(UUID caseId) {
    return delegate.findByUuid(caseId).await().indefinitely();
  }
}
