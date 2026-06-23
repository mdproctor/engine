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
package io.casehub.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.eidos.api.AgentDescriptor;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CaseDefinitionAgentDescriptorTest {

  private AgentDescriptor testDescriptor() {
    return AgentDescriptor.builder()
        .agentId("agent-1")
        .name("test-agent")
        .slot("analyst")
        .tenancyId("tenant-1")
        .build();
  }

  @Test
  void agentDescriptorForReturnsDescriptorWhenPresent() {
    var def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("case")
            .version("1.0")
            .agentDescriptor("worker-a", testDescriptor())
            .build();
    var result = def.agentDescriptorFor("worker-a");
    assertTrue(result.isPresent());
    assertEquals("agent-1", result.get().agentId());
  }

  @Test
  void agentDescriptorForReturnsEmptyWhenAbsent() {
    var def = CaseDefinition.builder().namespace("ns").name("case").version("1.0").build();
    assertEquals(Optional.empty(), def.agentDescriptorFor("worker-a"));
  }

  @Test
  void agentDescriptorForReturnsEmptyForWrongName() {
    var def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("case")
            .version("1.0")
            .agentDescriptor("worker-a", testDescriptor())
            .build();
    assertEquals(Optional.empty(), def.agentDescriptorFor("worker-b"));
  }
}
