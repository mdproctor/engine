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
package io.casehub.persistence.memory;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

/**
 * @DefaultBean CurrentPrincipal for test classpath — returns DEFAULT_TENANT_ID. For testing only.
 * If persistence-memory is on the compile classpath in production, all operations will silently use
 * DEFAULT_TENANT_ID.
 */
@DefaultBean
@ApplicationScoped
public class DefaultTestPrincipal implements CurrentPrincipal {

  @Override
  public String tenancyId() {
    return TenancyConstants.DEFAULT_TENANT_ID;
  }

  @Override
  public boolean isCrossTenantAdmin() {
    return false;
  }

  @Override
  public String actorId() {
    return "system";
  }

  @Override
  public Set<String> groups() {
    return Set.of();
  }
}
