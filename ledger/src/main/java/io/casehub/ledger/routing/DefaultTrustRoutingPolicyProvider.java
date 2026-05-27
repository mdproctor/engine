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
package io.casehub.ledger.routing;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Default {@link TrustRoutingPolicyProvider} — returns {@link TrustRoutingPolicy#DEFAULT} for all
 * capabilities. Yields to any deployment-specific {@code @Alternative @Priority(1)} provider.
 */
@DefaultBean
@ApplicationScoped
public class DefaultTrustRoutingPolicyProvider implements TrustRoutingPolicyProvider {

  @Override
  public TrustRoutingPolicy forCapability(final String capabilityName) {
    return TrustRoutingPolicy.DEFAULT;
  }
}
