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
package io.casehub.engine.runtime.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.casehub.api.model.AgentTaskTarget;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentMatch;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentTaskBindingResolverTest {

  AgentTaskBindingResolver resolver;
  CaseDefinitionRegistry caseDefinitionRegistry;
  AgentRegistry agentRegistry;

  @BeforeEach
  void setUp() {
    caseDefinitionRegistry = mock(CaseDefinitionRegistry.class);
    agentRegistry = mock(AgentRegistry.class);
    CurrentPrincipal principal = stubPrincipal("test-tenant");
    resolver = new AgentTaskBindingResolver();
    resolver.caseDefinitionRegistry = caseDefinitionRegistry;
    resolver.agentRegistry = agentRegistry;
    resolver.currentPrincipal = principal;
  }

  @Test
  void validate_bindingResolvesToAgent_noWarning() {
    AgentTaskTarget target = AgentTaskTarget.builder().capability("text-classification").build();
    Binding binding = Binding.builder().name("classify").agentTask(target).on("entry").build();
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("helpdesk")
            .version("1.0")
            .bindings(List.of(binding))
            .build();
    when(caseDefinitionRegistry.allDefinitions()).thenReturn(List.of(def));
    when(agentRegistry.find(
            AgentQuery.byCapabilityAndDomain("text-classification", null, "test-tenant")))
        .thenReturn(List.of(new AgentMatch(minimalAgent("classifier-agent"), null)));

    var result = resolver.validate();

    assertThat(result.resolved()).isEqualTo(1);
    assertThat(result.unresolved()).isEqualTo(0);
  }

  @Test
  void validate_bindingUnresolvable_countsAsUnresolved() {
    AgentTaskTarget target = AgentTaskTarget.builder().capability("nonexistent-cap").build();
    Binding binding = Binding.builder().name("missing-agent").agentTask(target).on("entry").build();
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("helpdesk")
            .version("1.0")
            .bindings(List.of(binding))
            .build();
    when(caseDefinitionRegistry.allDefinitions()).thenReturn(List.of(def));
    when(agentRegistry.find(any())).thenReturn(List.of());

    var result = resolver.validate();

    assertThat(result.resolved()).isEqualTo(0);
    assertThat(result.unresolved()).isEqualTo(1);
  }

  @Test
  void validate_nonAgentTaskBinding_skipped() {
    // Use a signal binding — a non-AgentTaskTarget binding type
    Binding binding =
        Binding.builder()
            .name("do-stuff")
            .signal(java.util.Map.of("type", "test"))
            .on("entry")
            .build();
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("basic")
            .version("1.0")
            .bindings(List.of(binding))
            .build();
    when(caseDefinitionRegistry.allDefinitions()).thenReturn(List.of(def));

    var result = resolver.validate();

    assertThat(result.resolved()).isEqualTo(0);
    assertThat(result.unresolved()).isEqualTo(0);
    verifyNoInteractions(agentRegistry);
  }

  @Test
  void validate_multipleBindingsAcrossDefinitions() {
    AgentTaskTarget t1 = AgentTaskTarget.builder().capability("classify").domain("legal").build();
    AgentTaskTarget t2 = AgentTaskTarget.builder().capability("summarize").build();
    Binding b1 = Binding.builder().name("b1").agentTask(t1).on("entry").build();
    Binding b2 = Binding.builder().name("b2").agentTask(t2).on("entry").build();
    CaseDefinition def1 =
        CaseDefinition.builder()
            .namespace("test")
            .name("case-a")
            .version("1.0")
            .bindings(List.of(b1))
            .build();
    CaseDefinition def2 =
        CaseDefinition.builder()
            .namespace("test")
            .name("case-b")
            .version("1.0")
            .bindings(List.of(b2))
            .build();
    when(caseDefinitionRegistry.allDefinitions()).thenReturn(List.of(def1, def2));
    when(agentRegistry.find(AgentQuery.byCapabilityAndDomain("classify", "legal", "test-tenant")))
        .thenReturn(List.of(new AgentMatch(minimalAgent("legal-agent"), null)));
    when(agentRegistry.find(AgentQuery.byCapabilityAndDomain("summarize", null, "test-tenant")))
        .thenReturn(List.of());

    var result = resolver.validate();

    assertThat(result.resolved()).isEqualTo(1);
    assertThat(result.unresolved()).isEqualTo(1);
  }

  @Test
  void validate_emptyDefinitions_noOp() {
    when(caseDefinitionRegistry.allDefinitions()).thenReturn(List.of());

    var result = resolver.validate();

    assertThat(result.resolved()).isEqualTo(0);
    assertThat(result.unresolved()).isEqualTo(0);
    verifyNoInteractions(agentRegistry);
  }

  private static AgentDescriptor minimalAgent(String agentId) {
    return AgentDescriptor.builder()
        .agentId(agentId)
        .name(agentId)
        .slot("default")
        .tenancyId("test-tenant")
        .build();
  }

  private static CurrentPrincipal stubPrincipal(String tenancyId) {
    return new CurrentPrincipal() {
      @Override
      public String actorId() {
        return "test-user";
      }

      @Override
      public Set<String> groups() {
        return Set.of();
      }

      @Override
      public String tenancyId() {
        return tenancyId;
      }

      @Override
      public boolean isCrossTenantAdmin() {
        return false;
      }
    };
  }
}
