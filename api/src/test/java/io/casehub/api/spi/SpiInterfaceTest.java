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
package io.casehub.api.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.PlatformStream;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpiInterfaceTest {

  @Test
  void engineCaseApi_hasCorrectDomain() {
    McpDomain domain = EngineCaseApi.class.getAnnotation(McpDomain.class);
    assertNotNull(domain);
    assertEquals("engine/cases", domain.value());
  }

  @Test
  void engineCaseApi_hasQueryAndMutationAndStreamMethods() {
    long queries =
        java.util.Arrays.stream(EngineCaseApi.class.getMethods())
            .filter(m -> m.isAnnotationPresent(PlatformQuery.class))
            .count();
    long mutations =
        java.util.Arrays.stream(EngineCaseApi.class.getMethods())
            .filter(m -> m.isAnnotationPresent(PlatformMutation.class))
            .count();
    long streams =
        java.util.Arrays.stream(EngineCaseApi.class.getMethods())
            .filter(m -> m.isAnnotationPresent(PlatformStream.class))
            .count();
    assertEquals(6, queries, "EngineCaseApi should have 6 queries");
    assertEquals(1, mutations, "EngineCaseApi should have 1 mutation");
    assertEquals(3, streams, "EngineCaseApi should have 3 streams");
  }

  @Test
  void allFiveInterfacesHaveMcpDomain() {
    assertNotNull(EngineCaseApi.class.getAnnotation(McpDomain.class));
    assertNotNull(EngineCaseControlApi.class.getAnnotation(McpDomain.class));
    assertNotNull(EngineCaseDefinitionApi.class.getAnnotation(McpDomain.class));
    assertNotNull(EngineEventLogApi.class.getAnnotation(McpDomain.class));
    assertNotNull(EnginePlanApi.class.getAnnotation(McpDomain.class));
  }

  @Test
  void domainNames_areHierarchical() {
    assertEquals("engine/cases", EngineCaseApi.class.getAnnotation(McpDomain.class).value());
    assertEquals(
        "engine/control", EngineCaseControlApi.class.getAnnotation(McpDomain.class).value());
    assertEquals(
        "engine/definitions", EngineCaseDefinitionApi.class.getAnnotation(McpDomain.class).value());
    assertEquals("engine/events", EngineEventLogApi.class.getAnnotation(McpDomain.class).value());
    assertEquals("engine/plan", EnginePlanApi.class.getAnnotation(McpDomain.class).value());
  }

  @Test
  void engineCaseApi_parameterNamesPreserved() throws Exception {
    Method m = EngineCaseApi.class.getMethod("getCaseById", UUID.class, String.class);
    assertEquals(
        "caseId", m.getParameters()[0].getName(), "-parameters flag must be set on api module");
    assertEquals("tenancyId", m.getParameters()[1].getName());
  }

  @Test
  void engineCaseControlApi_hasFourMutations() {
    long mutations =
        java.util.Arrays.stream(EngineCaseControlApi.class.getMethods())
            .filter(m -> m.isAnnotationPresent(PlatformMutation.class))
            .count();
    assertEquals(4, mutations);
  }

  @Test
  void enginePlanApi_hasQueriesAndStream() {
    long queries =
        java.util.Arrays.stream(EnginePlanApi.class.getMethods())
            .filter(m -> m.isAnnotationPresent(PlatformQuery.class))
            .count();
    long streams =
        java.util.Arrays.stream(EnginePlanApi.class.getMethods())
            .filter(m -> m.isAnnotationPresent(PlatformStream.class))
            .count();
    assertEquals(6, queries, "EnginePlanApi should have 6 queries");
    assertEquals(1, streams, "EnginePlanApi should have 1 stream");
  }
}
