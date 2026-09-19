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
package io.casehub.api.spi.stigmergy;

import io.casehub.api.model.stigmergy.IntegrationPolicy;
import io.casehub.api.model.stigmergy.SwarmBootstrapContext;
import jakarta.annotation.Nullable;
import java.util.Set;
import java.util.UUID;

public interface SwarmProvisioningAdvisor {

  ProvisioningAdvice advise(ProvisioningContext context);

  record ProvisioningContext(
      UUID caseId,
      SwarmBootstrapContext swarmState,
      Set<String> requestedCapabilities,
      IntegrationPolicy currentPolicy) {}

  record ProvisioningAdvice(
      boolean shouldProvision,
      @Nullable Set<String> adjustedCapabilities,
      @Nullable IntegrationPolicy adjustedPolicy,
      @Nullable String reasoning) {}
}
