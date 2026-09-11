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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.api.spi.DiscoveredWorker;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpWorkerFunctionProviderTest {

  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
  private McpSyncClient mockClient;

  private McpWorkerFunctionProvider createProvider(List<Tool> tools) {
    mockClient = mock(McpSyncClient.class);
    when(mockClient.listTools()).thenReturn(new ListToolsResult(tools, null, null));
    var provider =
        new McpWorkerFunctionProvider() {
          @Override
          McpSyncClient createDiscoveryClient(final McpTransport transport) {
            return mockClient;
          }
        };
    try {
      var field = McpWorkerFunctionProvider.class.getDeclaredField("endpointRegistry");
      field.setAccessible(true);
      field.set(provider, new McpEndpointRegistry());
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
    return provider;
  }

  @Test
  void handlesReturnsTrueForMcpBlock() throws Exception {
    var provider = createProvider(List.of());
    var node =
        yaml.readTree(
            """
            name: file-tools
            mcp:
              command: ["/bin/server"]
            """);
    assertThat(provider.handles(node)).isTrue();
  }

  @Test
  void handlesReturnsFalseWithoutMcpBlock() throws Exception {
    var provider = createProvider(List.of());
    var node =
        yaml.readTree(
            """
            name: local-worker
            capabilities: [analysis]
            """);
    assertThat(provider.handles(node)).isFalse();
  }

  @Test
  void discoverWorkersCreatesOneWorkerPerTool() throws Exception {
    var tools =
        List.of(
            Tool.builder("read-file", Map.of("type", "object")).description("Read a file").build(),
            Tool.builder("write-file", Map.of("type", "object"))
                .description("Write a file")
                .build());
    var provider = createProvider(tools);
    var node =
        yaml.readTree(
            """
            name: file-tools
            mcp:
              command: ["/bin/server"]
            """);

    List<DiscoveredWorker> discovered = provider.discoverWorkers(node);

    assertThat(discovered).hasSize(2);
    assertThat(discovered.get(0).workerName()).isEqualTo("file-tools--read-file");
    assertThat(discovered.get(0).capability().name()).isEqualTo("read-file");
    assertThat(discovered.get(0).capability().description()).isEqualTo("Read a file");
    assertThat(discovered.get(0).function()).isInstanceOf(McpWorkerFunction.class);
    assertThat(((McpWorkerFunction) discovered.get(0).function()).toolName())
        .isEqualTo("read-file");

    assertThat(discovered.get(1).workerName()).isEqualTo("file-tools--write-file");
    assertThat(discovered.get(1).capability().name()).isEqualTo("write-file");
  }

  @Test
  void singleToolUsesBaseNameWithoutSuffix() throws Exception {
    var tools =
        List.of(
            Tool.builder("read-file", Map.of("type", "object")).description("Read a file").build());
    var provider = createProvider(tools);
    var node =
        yaml.readTree(
            """
            name: file-reader
            mcp:
              command: ["/bin/server"]
            """);

    List<DiscoveredWorker> discovered = provider.discoverWorkers(node);

    assertThat(discovered).hasSize(1);
    assertThat(discovered.getFirst().workerName()).isEqualTo("file-reader");
  }

  @Test
  void explicitCapabilitiesFilterDiscoveredTools() throws Exception {
    var tools =
        List.of(
            Tool.builder("read-file", Map.of("type", "object")).description("Read").build(),
            Tool.builder("write-file", Map.of("type", "object")).description("Write").build(),
            Tool.builder("delete-file", Map.of("type", "object")).description("Delete").build());
    var provider = createProvider(tools);
    var node =
        yaml.readTree(
            """
            name: file-tools
            capabilities: [read-file, write-file]
            mcp:
              command: ["/bin/server"]
            """);

    List<DiscoveredWorker> discovered = provider.discoverWorkers(node);

    assertThat(discovered).hasSize(2);
    assertThat(discovered.stream().map(d -> d.capability().name()))
        .containsExactly("read-file", "write-file");
  }

  @Test
  void closesDiscoveryClientAfterListing() throws Exception {
    var tools =
        List.of(Tool.builder("read-file", Map.of("type", "object")).description("Read").build());
    var provider = createProvider(tools);
    var node =
        yaml.readTree(
            """
            name: tools
            mcp:
              command: ["/bin/server"]
            """);

    provider.discoverWorkers(node);

    verify(mockClient).closeGracefully();
  }

  @Test
  void parsesHttpTransport() throws Exception {
    var tools =
        List.of(Tool.builder("query", Map.of("type", "object")).description("Query").build());
    var provider = createProvider(tools);
    var node =
        yaml.readTree(
            """
            name: remote-tools
            mcp:
              url: https://example.com/mcp
              auth:
                type: bearer
                tokenConfigKey: mcp.token
            """);

    List<DiscoveredWorker> discovered = provider.discoverWorkers(node);

    assertThat(discovered).hasSize(1);
    McpWorkerFunction fn = (McpWorkerFunction) discovered.getFirst().function();
    assertThat(fn.transport()).isInstanceOf(McpTransport.Http.class);
    McpTransport.Http http = (McpTransport.Http) fn.transport();
    assertThat(http.url()).isEqualTo("https://example.com/mcp");
  }
}
