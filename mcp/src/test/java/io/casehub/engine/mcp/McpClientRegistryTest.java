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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.casehub.engine.common.internal.auth.AuthConfig;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpClientRegistryTest {

  private final McpClientRegistry registry =
      new McpClientRegistry() {
        @Override
        McpSyncClient createClient(final McpTransport transport) {
          return mock(McpSyncClient.class);
        }
      };

  @Test
  void getOrCreateReturnsSameClientForSameStdioCommand() {
    var transport = new McpTransport.Stdio(List.of("/bin/server"), Map.of());
    var c1 = registry.getOrCreate(transport);
    var c2 = registry.getOrCreate(transport);
    assertThat(c1).isSameAs(c2);
  }

  @Test
  void getOrCreateReturnsDifferentClientsForDifferentCommands() {
    var t1 = new McpTransport.Stdio(List.of("/bin/server1"), Map.of());
    var t2 = new McpTransport.Stdio(List.of("/bin/server2"), Map.of());
    var c1 = registry.getOrCreate(t1);
    var c2 = registry.getOrCreate(t2);
    assertThat(c1).isNotSameAs(c2);
  }

  @Test
  void getOrCreateReturnsSameClientForSameHttpUrl() {
    var t1 = new McpTransport.Http("https://example.com/mcp", AuthConfig.NONE);
    var t2 = new McpTransport.Http("https://example.com/mcp", AuthConfig.NONE);
    var c1 = registry.getOrCreate(t1);
    var c2 = registry.getOrCreate(t2);
    assertThat(c1).isSameAs(c2);
  }

  @Test
  void getOrCreateThrowsOnAuthConflictForSameUrl() {
    registry.getOrCreate(new McpTransport.Http("https://example.com/mcp", AuthConfig.NONE));

    assertThatThrownBy(
            () ->
                registry.getOrCreate(
                    new McpTransport.Http(
                        "https://example.com/mcp",
                        new AuthConfig(AuthConfig.AuthType.BEARER, "key"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflict");
  }

  @Test
  void getOrCreateThrowsOnEnvConflictForSameCommand() {
    registry.getOrCreate(new McpTransport.Stdio(List.of("/bin/server"), Map.of("KEY", "val1")));

    assertThatThrownBy(
            () ->
                registry.getOrCreate(
                    new McpTransport.Stdio(List.of("/bin/server"), Map.of("KEY", "val2"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflict");
  }

  @Test
  void evictRemovesCachedClient() {
    var transport = new McpTransport.Stdio(List.of("/bin/server"), Map.of());
    var c1 = registry.getOrCreate(transport);
    registry.evict(transport);
    var c2 = registry.getOrCreate(transport);
    assertThat(c1).isNotSameAs(c2);
  }
}
