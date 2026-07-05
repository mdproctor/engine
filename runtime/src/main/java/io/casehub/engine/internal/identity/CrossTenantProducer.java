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
package io.casehub.engine.internal.identity;

import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.engine.common.qualifier.EngineSystem;
import io.casehub.engine.common.spi.ReactiveCrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.ReactiveCrossTenantEventLogRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * Produces @CrossTenant-qualified cross-tenant repository beans.
 *
 * <p>The @EngineSystem SystemCurrentPrincipal check is a contract assertion: if
 * SystemCurrentPrincipal.isCrossTenantAdmin() ever returns false (e.g. during testing the guard
 * itself), this producer fails at startup rather than silently granting access. It is aspirational
 * scaffolding for when the platform ships a runtime-evaluated system principal.
 */
@ApplicationScoped
public class CrossTenantProducer {

  @Inject @EngineSystem SystemCurrentPrincipal systemPrincipal;
  @Inject ReactiveCrossTenantEventLogRepository eventLogRepo;
  @Inject ReactiveCrossTenantCaseInstanceRepository caseInstanceRepo;

  @Produces
  @CrossTenant
  @ApplicationScoped
  public ReactiveCrossTenantEventLogRepository produceEventLog() {
    if (!systemPrincipal.isCrossTenantAdmin()) {
      throw new IllegalStateException(
          "SystemCurrentPrincipal.isCrossTenantAdmin() must return true — engine#405");
    }
    return eventLogRepo;
  }

  @Produces
  @CrossTenant
  @ApplicationScoped
  public ReactiveCrossTenantCaseInstanceRepository produceCaseInstance() {
    if (!systemPrincipal.isCrossTenantAdmin()) {
      throw new IllegalStateException(
          "SystemCurrentPrincipal.isCrossTenantAdmin() must return true — engine#405");
    }
    return caseInstanceRepo;
  }
}
