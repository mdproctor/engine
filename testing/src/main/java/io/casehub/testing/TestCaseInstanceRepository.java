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
package io.casehub.testing;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.persistence.memory.InMemoryCaseInstanceRepository;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.UUID;

/** Auto-selected in-memory {@link CaseInstanceRepository} for {@code @QuarkusTest}. */
@Alternative
@Priority(1)
@ApplicationScoped
public class TestCaseInstanceRepository extends InMemoryCaseInstanceRepository {

  @Override
  public CaseInstance findByUuid(UUID uuid, String tenancyId) {
    // Test infrastructure — tenancy enforcement is in TenantAwareRepository (JPA/RLS).
    // Event bus handlers may resolve a different CurrentPrincipal.tenancyId() than the one
    // used at save() time (e.g., cross-tenant recovery publishing events consumed by
    // tenant-scoped handlers). The parent class filters by tenancyId, which breaks tests.
    // TODO: Thread tenancyId through event bus messages (tracked in engine#680).
    return findByUuid(uuid);
  }
}
