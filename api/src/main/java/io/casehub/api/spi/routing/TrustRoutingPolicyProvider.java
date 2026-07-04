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
package io.casehub.api.spi.routing;

import io.casehub.platform.api.routing.NamedStrategy;

/**
 * SPI for resolving the routing policy to apply for a given capability.
 *
 * <p>Deployments override with {@code @ApplicationScoped @Alternative @Priority(1)} to provide
 * per-capability policies. For example, devtown's {@code DevtownCapabilityRegistry} can implement
 * this interface to expose its per-capability routing configuration.
 *
 * <p>The default implementation returns {@link
 * io.casehub.api.spi.routing.TrustRoutingPolicy#DEFAULT} for all capabilities.
 */
public interface TrustRoutingPolicyProvider extends NamedStrategy {

  /**
   * Return the routing policy for the given capability name. Never returns null — use {@link
   * io.casehub.api.spi.routing.TrustRoutingPolicy#DEFAULT} as the fallback.
   */
  io.casehub.api.spi.routing.TrustRoutingPolicy forCapability(String capabilityName);
}
