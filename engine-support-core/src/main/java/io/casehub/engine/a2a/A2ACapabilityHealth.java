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
package io.casehub.engine.a2a;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.CapabilityHealth;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class A2ACapabilityHealth implements CapabilityHealth {

  private static final Logger LOG = Logger.getLogger(A2ACapabilityHealth.class);

  @Inject A2AEndpointRegistry endpointRegistry;
  @Inject A2AClientRegistry clientRegistry;

  @Override
  public CapabilityStatus probe(
      AgentDescriptor descriptor, String capabilityTag, ProbeContext context) {
    return endpointRegistry
        .lookup(descriptor.agentId())
        .map(
            function -> {
              A2AClient client = clientRegistry.getOrCreate(function.endpoint(), function.auth());
              if (client.checkHealth()) {
                return (CapabilityStatus) new CapabilityStatus.Ready();
              }
              LOG.warnf(
                  "A2A agent '%s' at %s is unreachable", descriptor.agentId(), function.endpoint());
              return (CapabilityStatus)
                  new CapabilityStatus.Unavailable(
                      "A2A endpoint unreachable: " + function.endpoint());
            })
        .orElse(new CapabilityStatus.Ready());
  }
}
