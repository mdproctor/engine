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
package io.casehub.engine.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.graphql.dto.CaseFilterInput;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.graphql.PageInput;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseQueryResolverTest {

  private CaseQueryResolver resolver;
  private CaseInstanceRepository instanceRepository;
  private CaseDefinitionRegistry definitionRegistry;
  private CaseHubRuntime runtime;
  private CurrentPrincipal currentPrincipal;

  @BeforeEach
  void setUp() {
    resolver = new CaseQueryResolver();
    instanceRepository = mock(CaseInstanceRepository.class);
    definitionRegistry = mock(CaseDefinitionRegistry.class);
    runtime = mock(CaseHubRuntime.class);
    currentPrincipal = mock(CurrentPrincipal.class);
    var planItemStore = mock(PlanItemStore.class);

    resolver.instanceRepository = instanceRepository;
    resolver.definitionRegistry = definitionRegistry;
    resolver.runtime = runtime;
    resolver.currentPrincipal = currentPrincipal;
    resolver.planItemStore = planItemStore;

    when(currentPrincipal.tenancyId()).thenReturn("test-tenant");
  }

  @Test
  void caseByIdReturnsCaseWhenFound() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = createTestInstance(caseId, CaseStatus.RUNNING);
    when(instanceRepository.findByUuid(caseId, "test-tenant")).thenReturn(instance);

    var result = resolver.caseById(caseId);

    assertThat(result).isNotNull();
    assertThat(result.caseId()).isEqualTo(caseId);
    assertThat(result.status()).isEqualTo(CaseStatus.RUNNING);
    assertThat(result.namespace()).isEqualTo("test-ns");
    assertThat(result.name()).isEqualTo("Test Case");
  }

  @Test
  void caseByIdReturnsNullWhenNotFound() {
    UUID caseId = UUID.randomUUID();
    when(instanceRepository.findByUuid(caseId, "test-tenant")).thenReturn(null);

    var result = resolver.caseById(caseId);

    assertThat(result).isNull();
  }

  @Test
  void casesReturnsPaginatedResults() {
    CaseInstance instance = createTestInstance(UUID.randomUUID(), CaseStatus.RUNNING);
    when(instanceRepository.query(any(), eq("test-tenant"))).thenReturn(List.of(instance));
    when(instanceRepository.count(any(), eq("test-tenant"))).thenReturn(1L);

    var result = resolver.cases(null, new PageInput(0, 10, null));

    assertThat(result).isNotNull();
    assertThat(result.items()).hasSize(1);
    assertThat(result.pageInfo().totalCount()).isEqualTo(1);
    assertThat(result.pageInfo().hasNext()).isFalse();
  }

  @Test
  void casesAppliesFilter() {
    var filter = new CaseFilterInput(CaseStatus.RUNNING, "acme", null);
    when(instanceRepository.query(any(), eq("test-tenant"))).thenReturn(List.of());
    when(instanceRepository.count(any(), eq("test-tenant"))).thenReturn(0L);

    var result = resolver.cases(filter, new PageInput(0, 10, null));

    assertThat(result.items()).isEmpty();
    assertThat(result.pageInfo().totalCount()).isEqualTo(0);
  }

  @Test
  void caseDefinitionsReturnsAllDefinitions() {
    CaseDefinition def =
        CaseDefinition.builder().namespace("test").name("TestCase").version("1.0.0").build();
    when(definitionRegistry.allDefinitions()).thenReturn(List.of(def));

    var result = resolver.caseDefinitions(new PageInput(0, 10, null));

    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0).namespace()).isEqualTo("test");
    assertThat(result.items().get(0).name()).isEqualTo("TestCase");
  }

  @Test
  void caseDefinitionByIdentityReturnsMatch() {
    CaseDefinition def =
        CaseDefinition.builder().namespace("test").name("TestCase").version("1.0.0").build();
    CaseMetaModel meta = new CaseMetaModel();
    when(definitionRegistry.findByIdentity("test", "TestCase", "1.0.0"))
        .thenReturn(Optional.of(meta));
    when(definitionRegistry.getCaseDefinition(meta)).thenReturn(def);

    var result = resolver.caseDefinition("test", "TestCase", "1.0.0");

    assertThat(result).isNotNull();
    assertThat(result.namespace()).isEqualTo("test");
  }

  @Test
  void caseDefinitionReturnsNullWhenNotFound() {
    when(definitionRegistry.findByIdentity("x", "y", "z")).thenReturn(Optional.empty());

    var result = resolver.caseDefinition("x", "y", "z");

    assertThat(result).isNull();
  }

  @Test
  void caseEventsReturnsEventLog() {
    var record =
        new CaseEventLogRecord(
            CaseHubEventType.CASE_STARTED, EventStreamType.CASE, Instant.now(), null, null);
    UUID caseId = UUID.randomUUID();
    when(runtime.eventLog(caseId)).thenReturn(List.of(record));

    var result = resolver.caseEvents(caseId, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).eventType()).isEqualTo("CASE_STARTED");
  }

  private CaseInstance createTestInstance(UUID caseId, CaseStatus status) {
    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace("test-ns");
    meta.setName("Test Case");
    meta.setVersion("1.0.0");
    meta.setCreatedAt(Instant.now());

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setState(status);
    instance.setCaseMetaModel(meta);
    instance.setActorId("test-actor");
    return instance;
  }
}
