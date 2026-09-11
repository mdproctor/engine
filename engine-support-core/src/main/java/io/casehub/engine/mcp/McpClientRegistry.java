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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

@ApplicationScoped
public class McpClientRegistry {

  private static final Logger LOG = Logger.getLogger(McpClientRegistry.class);

  private final ConcurrentHashMap<String, McpSyncClient> clients = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, McpTransport> transportConfigs =
      new ConcurrentHashMap<>();

  public McpSyncClient getOrCreate(final McpTransport transport) {
    final String key = transportKey(transport);
    validateNoConflict(key, transport);
    return clients.computeIfAbsent(key, k -> createClient(transport));
  }

  public void evict(final McpTransport transport) {
    final String key = transportKey(transport);
    final McpSyncClient removed = clients.remove(key);
    transportConfigs.remove(key);
    if (removed != null) {
      closeQuietly(removed);
    }
  }

  void shutdown(@Observes final ShutdownEvent event) {
    clients.values().forEach(this::closeQuietly);
    clients.clear();
    transportConfigs.clear();
  }

  McpSyncClient createClient(final McpTransport transport) {
    final McpClientTransport sdkTransport = McpTransportFactory.create(transport);
    final McpSyncClient client =
        McpClient.sync(sdkTransport).capabilities(ClientCapabilities.builder().build()).build();
    client.initialize();
    return client;
  }

  private String transportKey(final McpTransport transport) {
    return switch (transport) {
      case McpTransport.Stdio stdio -> "stdio:" + String.join(" ", stdio.command());
      case McpTransport.Http http -> "http:" + http.url();
    };
  }

  private void validateNoConflict(final String key, final McpTransport transport) {
    final McpTransport existing = transportConfigs.putIfAbsent(key, transport);
    if (existing != null && !existing.equals(transport)) {
      throw new IllegalArgumentException(
          "MCP transport conflict for " + key + ": existing and new configs differ");
    }
  }

  private void closeQuietly(final McpSyncClient client) {
    try {
      client.closeGracefully();
    } catch (Exception e) {
      LOG.debugf("Error closing MCP client: %s", e.getMessage());
    }
  }
}
