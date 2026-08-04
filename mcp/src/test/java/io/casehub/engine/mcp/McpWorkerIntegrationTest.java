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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
class McpWorkerIntegrationTest {

  @Inject McpIntegrationCaseHub caseHub;
  @Inject CaseInstanceCache caseInstanceCache;

  @Test
  void mcpWorkerExecutesAndCompletesCase() {
    UUID caseId = caseHub.startCase(Map.of("status", "ready"));
    assertNotNull(caseId);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertNotNull(instance, "instance should be in cache");
              assertEquals(
                  CaseStatus.COMPLETED,
                  instance.getState(),
                  "case should reach COMPLETED (was: " + instance.getState() + ")");
            });
  }

  @ApplicationScoped
  public static class McpIntegrationCaseHub extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      Capability capability =
          Capability.builder()
              .name("query")
              .description("Query data")
              .inputSchema(".")
              .outputSchema(".")
              .build();

      Worker worker =
          Worker.builder()
              .name("mcp-tool")
              .capabilityName("query")
              .function(
                  new McpWorkerFunction(
                      new McpTransport.Stdio(List.of("/bin/echo"), Map.of()), "query"))
              .build();

      Binding binding =
          Binding.builder()
              .name("run-query")
              .capability(capability)
              .on(new ContextChangeTrigger(".status == \"ready\""))
              .build();

      Goal goal =
          Goal.builder()
              .name("done")
              .condition(".queryResult != null")
              .kind(GoalKind.SUCCESS)
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("mcp-integration")
          .version("1.0.0")
          .title("MCP integration test")
          .capabilities(capability)
          .workers(worker)
          .bindings(binding)
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }

  @Alternative
  @Priority(1)
  @ApplicationScoped
  public static class MockMcpClientRegistry extends McpClientRegistry {
    @Override
    McpSyncClient createClient(final McpTransport transport) {
      McpSyncClient mock = mock(McpSyncClient.class);
      when(mock.callTool(any()))
          .thenReturn(
              new CallToolResult(
                  List.of(new TextContent(null, "{\"queryResult\":\"found\"}", null)),
                  false,
                  null,
                  null));
      return mock;
    }
  }
}
