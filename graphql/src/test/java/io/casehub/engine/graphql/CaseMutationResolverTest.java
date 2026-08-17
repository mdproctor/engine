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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.graphql.dto.StartCaseInput;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.graphql.scalar.Json;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseMutationResolverTest {

  private CaseMutationResolver resolver;
  private CaseDefinitionRegistry definitionRegistry;
  private CaseHubRuntime runtime;
  private CaseInstanceRepository instanceRepository;
  private CurrentPrincipal currentPrincipal;

  @BeforeEach
  void setUp() {
    resolver = new CaseMutationResolver();
    definitionRegistry = mock(CaseDefinitionRegistry.class);
    runtime = mock(CaseHubRuntime.class);
    instanceRepository = mock(CaseInstanceRepository.class);
    currentPrincipal = mock(CurrentPrincipal.class);

    resolver.definitionRegistry = definitionRegistry;
    resolver.runtime = runtime;
    resolver.instanceRepository = instanceRepository;
    resolver.currentPrincipal = currentPrincipal;

    when(currentPrincipal.tenancyId()).thenReturn("test-tenant");
  }

  @Test
  void startCaseCreatesAndReturnsCaseInstance() {
    CaseDefinition def =
        CaseDefinition.builder().namespace("test").name("TestCase").version("1.0.0").build();
    CaseMetaModel meta = new CaseMetaModel();
    UUID caseId = UUID.randomUUID();

    when(definitionRegistry.findByIdentity("test", "TestCase", "1.0.0"))
        .thenReturn(Optional.of(meta));
    when(definitionRegistry.getCaseDefinition(meta)).thenReturn(def);
    when(runtime.startCase(eq(def), any())).thenReturn(caseId);

    CaseInstance instance = createTestInstance(caseId, CaseStatus.RUNNING);
    when(instanceRepository.findByUuid(caseId, "test-tenant")).thenReturn(instance);

    var input = new StartCaseInput("test", "TestCase", "1.0.0", null);
    var result = resolver.startCase(input);

    assertThat(result).isNotNull();
    assertThat(result.caseId()).isEqualTo(caseId);
    assertThat(result.status()).isEqualTo(CaseStatus.RUNNING);
  }

  @Test
  void startCaseWithContextPassesContextToRuntime() {
    CaseDefinition def =
        CaseDefinition.builder().namespace("test").name("TestCase").version("1.0.0").build();
    CaseMetaModel meta = new CaseMetaModel();
    UUID caseId = UUID.randomUUID();

    when(definitionRegistry.findByIdentity("test", "TestCase", "1.0.0"))
        .thenReturn(Optional.of(meta));
    when(definitionRegistry.getCaseDefinition(meta)).thenReturn(def);
    when(runtime.startCase(eq(def), any())).thenReturn(caseId);

    CaseInstance instance = createTestInstance(caseId, CaseStatus.RUNNING);
    when(instanceRepository.findByUuid(caseId, "test-tenant")).thenReturn(instance);

    var context = Json.of(Map.of("customer", "acme"));
    var input = new StartCaseInput("test", "TestCase", "1.0.0", context);
    resolver.startCase(input);

    verify(runtime).startCase(eq(def), eq(Map.of("customer", "acme")));
  }

  @Test
  void startCaseThrowsWhenDefinitionNotFound() {
    when(definitionRegistry.findByIdentity("x", "y", "z")).thenReturn(Optional.empty());

    var input = new StartCaseInput("x", "y", "z", null);
    assertThatThrownBy(() -> resolver.startCase(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No definition");
  }

  @Test
  void signalCaseSignalsAndReturnsAccepted() {
    UUID caseId = UUID.randomUUID();

    var result = resolver.signalCase(caseId, "/data/status", "approved");

    assertThat(result.caseId()).isEqualTo(caseId);
    assertThat(result.accepted()).isTrue();
    verify(runtime).signal(caseId, "/data/status", "approved");
  }

  @Test
  void suspendCaseSuspendsAndReturnsStatus() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = createTestInstance(caseId, CaseStatus.SUSPENDED);
    when(instanceRepository.findByUuid(caseId, "test-tenant")).thenReturn(instance);

    var result = resolver.suspendCase(caseId);

    assertThat(result.caseId()).isEqualTo(caseId);
    assertThat(result.status()).isEqualTo(CaseStatus.SUSPENDED);
    verify(runtime).suspendCase(caseId);
  }

  @Test
  void resumeCaseResumesAndReturnsStatus() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = createTestInstance(caseId, CaseStatus.RUNNING);
    when(instanceRepository.findByUuid(caseId, "test-tenant")).thenReturn(instance);

    var result = resolver.resumeCase(caseId);

    assertThat(result.caseId()).isEqualTo(caseId);
    assertThat(result.status()).isEqualTo(CaseStatus.RUNNING);
    verify(runtime).resumeCase(caseId);
  }

  @Test
  void cancelCaseCancelsAndReturnsStatus() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = createTestInstance(caseId, CaseStatus.CANCELLED);
    when(instanceRepository.findByUuid(caseId, "test-tenant")).thenReturn(instance);

    var result = resolver.cancelCase(caseId);

    assertThat(result.caseId()).isEqualTo(caseId);
    assertThat(result.status()).isEqualTo(CaseStatus.CANCELLED);
    verify(runtime).cancelCase(caseId);
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
