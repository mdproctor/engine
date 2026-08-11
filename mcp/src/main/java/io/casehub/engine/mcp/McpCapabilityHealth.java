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
package io.casehub.engine.mcp;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.CapabilityHealth;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class McpCapabilityHealth implements CapabilityHealth {

  private static final Logger LOG = Logger.getLogger(McpCapabilityHealth.class);

  @Inject McpEndpointRegistry endpointRegistry;
  @Inject McpClientRegistry clientRegistry;

  @Override
  public CapabilityStatus probe(
      AgentDescriptor descriptor, String capabilityTag, ProbeContext context) {
    return endpointRegistry
        .lookup(descriptor.agentId())
        .map(
            transport -> {
              try {
                McpSyncClient client = clientRegistry.getOrCreate(transport);
                client.ping();
                return (CapabilityStatus) new CapabilityStatus.Ready();
              } catch (Exception e) {
                LOG.warnf(
                    "MCP server for '%s' is unreachable: %s", descriptor.agentId(), e.getMessage());
                return (CapabilityStatus)
                    new CapabilityStatus.Unavailable("MCP server unreachable: " + e.getMessage());
              }
            })
        .orElse(new CapabilityStatus.Ready());
  }
}
