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
package io.casehub.engine.common.spi.event;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.internal.context.CaseContextImpl;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CaseLifecycleEvent")
class CaseLifecycleEventTest {

  private static final UUID CASE_ID = UUID.randomUUID();
  private static final String TENANCY_ID = "tenant-1";
  private static final String COMMAND_TYPE = "CompleteCase";
  private static final String EVENT_TYPE = "CaseCompleted";
  private static final String ACTOR_ID = "agent:reviewer@v1";
  private static final String ACTOR_ROLE = "System";
  private static final String TRACE_ID = "abc-123";

  @Nested
  @DisplayName("of(CaseInstance, ...) factory")
  class CaseInstanceFactory {

    @Test
    @DisplayName("extracts all fields from a fully populated CaseInstance")
    void fullyPopulated() {
      CaseInstance ci = buildCaseInstance("my-case", "io.casehub", Map.of("key", "value"));
      CaseLifecycleEvent event =
          CaseLifecycleEvent.of(ci, COMMAND_TYPE, EVENT_TYPE, ACTOR_ID, ACTOR_ROLE, TRACE_ID);

      assertEquals(CASE_ID, event.caseId());
      assertEquals(TENANCY_ID, event.tenancyId());
      assertEquals(COMMAND_TYPE, event.commandType());
      assertEquals(EVENT_TYPE, event.eventType());
      assertEquals(CaseStatus.RUNNING.name(), event.caseStatus());
      assertEquals(ACTOR_ID, event.actorId());
      assertEquals(ACTOR_ROLE, event.actorRole());
      assertEquals(TRACE_ID, event.traceId());
      assertEquals("my-case", event.caseDefinitionName());
      assertEquals("io.casehub", event.namespace());
      assertNotNull(event.contextSnapshot());
      assertEquals("value", event.contextSnapshot().get("key").asText());
    }

    @Test
    @DisplayName("handles null CaseMetaModel gracefully")
    void nullMetaModel() {
      CaseInstance ci = buildCaseInstance(null, null, Map.of());
      ci.setCaseMetaModel(null);
      CaseLifecycleEvent event =
          CaseLifecycleEvent.of(ci, COMMAND_TYPE, EVENT_TYPE, null, ACTOR_ROLE, null);

      assertEquals(CASE_ID, event.caseId());
      assertNull(event.caseDefinitionName());
      assertNull(event.namespace());
    }

    @Test
    @DisplayName("handles null CaseContext gracefully")
    void nullContext() {
      CaseInstance ci = new CaseInstance();
      ci.setUuid(CASE_ID);
      ci.tenancyId = TENANCY_ID;
      ci.setState(CaseStatus.RUNNING);
      CaseMetaModel mm = new CaseMetaModel();
      mm.setName("my-case");
      mm.setNamespace("io.casehub");
      ci.setCaseMetaModel(mm);
      ci.setCaseContext(null);
      CaseLifecycleEvent event =
          CaseLifecycleEvent.of(ci, COMMAND_TYPE, EVENT_TYPE, null, ACTOR_ROLE, null);

      assertEquals("my-case", event.caseDefinitionName());
      assertNull(event.contextSnapshot());
    }
  }

  @Nested
  @DisplayName("of(UUID, String, ...) overloaded factory")
  class OverloadedFactory {

    @Test
    @DisplayName("returns null for all enrichment fields")
    void enrichmentFieldsNull() {
      CaseLifecycleEvent event =
          CaseLifecycleEvent.of(
              CASE_ID,
              TENANCY_ID,
              COMMAND_TYPE,
              EVENT_TYPE,
              CaseStatus.COMPLETED.name(),
              ACTOR_ID,
              ACTOR_ROLE,
              TRACE_ID);

      assertEquals(CASE_ID, event.caseId());
      assertEquals(TENANCY_ID, event.tenancyId());
      assertEquals(COMMAND_TYPE, event.commandType());
      assertEquals(EVENT_TYPE, event.eventType());
      assertEquals(CaseStatus.COMPLETED.name(), event.caseStatus());
      assertNull(event.caseDefinitionName());
      assertNull(event.namespace());
      assertNull(event.contextSnapshot());
    }
  }

  private CaseInstance buildCaseInstance(
      String definitionName, String namespace, Map<String, Object> contextData) {
    CaseInstance ci = new CaseInstance();
    ci.setUuid(CASE_ID);
    ci.tenancyId = TENANCY_ID;
    ci.setState(CaseStatus.RUNNING);
    if (definitionName != null) {
      CaseMetaModel mm = new CaseMetaModel();
      mm.setName(definitionName);
      mm.setNamespace(namespace);
      ci.setCaseMetaModel(mm);
    }
    if (contextData != null) {
      ci.setCaseContext(new CaseContextImpl(contextData));
    }
    return ci;
  }
}
