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
package io.casehub.engine.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.WorkOrchestrator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class CasehubDispatchTest {

  private FlowExecutionRegistry registry;
  private WorkOrchestrator orchestrator;
  private EventLogRepository eventLogRepository;
  private io.casehub.engine.common.spi.cache.CaseInstanceCache caseInstanceCache;
  private CallableDispatchRegistry dispatchRegistry;
  private CasehubDispatch dispatch;

  @BeforeEach
  void setUp() {
    registry = mock(FlowExecutionRegistry.class);
    orchestrator = mock(WorkOrchestrator.class);
    eventLogRepository = mock(EventLogRepository.class);

    // Default: fire-and-forget subscribe returns immediately
    when(eventLogRepository.appendAndReturnId(any(), any())).thenReturn(1L);

    caseInstanceCache = mock(io.casehub.engine.common.spi.cache.CaseInstanceCache.class);
    dispatchRegistry = new CallableDispatchRegistry();
    dispatch =
        new CasehubDispatch(
            registry, orchestrator, eventLogRepository, caseInstanceCache, dispatchRegistry);
    dispatch.register();
  }

  // ---- self-registration ----------------------------------------------

  @Test
  void register_registers_casehub_dispatch_in_registry() {
    assertThat(dispatchRegistry.canHandle("casehub:dispatch")).isTrue();
  }

  @Test
  void registered_dispatcher_delegates_to_dispatch_method() throws Exception {
    final String instanceId = "wf-reg";
    final CaseInstance instance = mockInstance();
    stubRegistry(instanceId, instance);

    final Map<String, Object> output = Map.of("delegated", true);
    when(orchestrator.submit(eq(instance), any(WorkRequest.class)))
        .thenReturn(CompletableFuture.completedStage(WorkResult.completed("k", output, "w")));

    final Map<String, Object> result =
        dispatchRegistry
            .get("casehub:dispatch")
            .dispatch(instanceId, Map.of("capability", "test-cap"))
            .get();

    assertThat(result).isEqualTo(output);
  }

  @Test
  void registered_dispatcher_throws_on_missing_capability() {
    assertThatThrownBy(() -> dispatchRegistry.get("casehub:dispatch").dispatch("wf-x", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capability");
  }

  // ---- happy path -----------------------------------------------------

  @Test
  void dispatch_success_emits_dispatched_then_completed_events() throws Exception {
    final String instanceId = "wf-123";
    final CaseInstance instance = mockInstance();
    stubRegistry(instanceId, instance);

    final Map<String, Object> output = Map.of("result", "done");
    final WorkResult workResult = WorkResult.completed("key", output, "worker-1");
    when(orchestrator.submit(eq(instance), any(WorkRequest.class)))
        .thenReturn(CompletableFuture.completedStage(workResult));

    final Map<String, Object> result = dispatch.dispatch(instanceId, "analyze").get();

    assertThat(result).isEqualTo(output);

    // Verify both step events were appended
    final ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository, org.mockito.Mockito.times(2))
        .appendAndReturnId(logCaptor.capture(), any());

    final List<EventLog> logs = logCaptor.getAllValues();
    assertThat(logs.get(0).getEventType()).isEqualTo(CaseHubEventType.WORKFLOW_STEP_DISPATCHED);
    assertThat(logs.get(1).getEventType()).isEqualTo(CaseHubEventType.WORKFLOW_STEP_COMPLETED);
  }

  @Test
  void dispatched_event_is_appended_before_orchestrator_submit_is_called() throws Exception {
    final String instanceId = "wf-456";
    final CaseInstance instance = mockInstance();
    stubRegistry(instanceId, instance);

    when(orchestrator.submit(eq(instance), any(WorkRequest.class)))
        .thenReturn(CompletableFuture.completedStage(WorkResult.completed("k", Map.of(), "w")));

    dispatch.dispatch(instanceId, "cap").get();

    final InOrder order = inOrder(eventLogRepository, orchestrator);
    order.verify(eventLogRepository).appendAndReturnId(any(), any()); // DISPATCHED
    order.verify(orchestrator).submit(any(), any());
  }

  // ---- failure path ---------------------------------------------------

  @Test
  void dispatch_failure_emits_dispatched_then_failed_events() throws Exception {
    final String instanceId = "wf-789";
    final CaseInstance instance = mockInstance();
    stubRegistry(instanceId, instance);

    final CompletableFuture<WorkResult> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("capability not found"));
    when(orchestrator.submit(eq(instance), any(WorkRequest.class))).thenReturn(failed);

    // dispatch() future itself must complete exceptionally
    final CompletableFuture<Map<String, Object>> future = dispatch.dispatch(instanceId, "cap");
    assertThat(future).isCompletedExceptionally();

    final ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository, org.mockito.Mockito.times(2))
        .appendAndReturnId(logCaptor.capture(), any());

    final List<EventLog> logs = logCaptor.getAllValues();
    assertThat(logs.get(0).getEventType()).isEqualTo(CaseHubEventType.WORKFLOW_STEP_DISPATCHED);
    assertThat(logs.get(1).getEventType()).isEqualTo(CaseHubEventType.WORKFLOW_STEP_FAILED);
  }

  @Test
  void dispatch_failure_does_not_emit_completed_event() throws Exception {
    final String instanceId = "wf-fail";
    final CaseInstance instance = mockInstance();
    stubRegistry(instanceId, instance);

    final CompletableFuture<WorkResult> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("routing failure"));
    when(orchestrator.submit(any(), any())).thenReturn(failed);

    dispatch.dispatch(instanceId, "cap").exceptionally(e -> null).get();

    final ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository, org.mockito.Mockito.times(2))
        .appendAndReturnId(logCaptor.capture(), any());

    // The second event must be FAILED, not COMPLETED
    assertThat(logCaptor.getAllValues().get(1).getEventType())
        .isEqualTo(CaseHubEventType.WORKFLOW_STEP_FAILED)
        .isNotEqualTo(CaseHubEventType.WORKFLOW_STEP_COMPLETED);
  }

  // ---- WorkRequest routing  ------------------------------------------

  @Test
  void dispatch_submits_with_correct_capability_name() throws Exception {
    final String instanceId = "wf-req";
    final CaseInstance instance = mockInstance();
    stubRegistry(instanceId, instance);

    when(orchestrator.submit(any(), any()))
        .thenReturn(CompletableFuture.completedStage(WorkResult.completed("k", Map.of(), "w")));

    dispatch.dispatch(instanceId, "generate-report").get();

    final ArgumentCaptor<WorkRequest> reqCaptor = ArgumentCaptor.forClass(WorkRequest.class);
    verify(orchestrator).submit(eq(instance), reqCaptor.capture());
    assertThat(reqCaptor.getValue().capability()).isEqualTo("generate-report");
  }

  // ---- identity from PropagationContext --------------------------------

  @Test
  void dispatch_records_identity_from_propagation_context_in_event_metadata() throws Exception {
    final String instanceId = "wf-identity";
    final CaseInstance instance = mockInstanceWithIdentity("user-42", "admin,analyst");
    stubRegistry(instanceId, instance);

    when(orchestrator.submit(eq(instance), any(WorkRequest.class)))
        .thenReturn(CompletableFuture.completedStage(WorkResult.completed("k", Map.of(), "w")));

    dispatch.dispatch(instanceId, "analyze").get();

    final ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository, org.mockito.Mockito.atLeastOnce())
        .appendAndReturnId(logCaptor.capture(), any());

    logCaptor
        .getAllValues()
        .forEach(
            log -> {
              assertThat(log.getMetadata().get("actorId").asText()).isEqualTo("user-42");
              assertThat(log.getMetadata().get("roles").asText()).isEqualTo("admin,analyst");
            });
  }

  @Test
  void dispatch_handles_missing_propagation_context_gracefully() throws Exception {
    final String instanceId = "wf-no-ctx";
    final CaseInstance instance = mockInstance();
    when(instance.getPropagationContext()).thenReturn(null);
    stubRegistry(instanceId, instance);

    when(orchestrator.submit(eq(instance), any(WorkRequest.class)))
        .thenReturn(CompletableFuture.completedStage(WorkResult.completed("k", Map.of(), "w")));

    dispatch.dispatch(instanceId, "analyze").get();

    final ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository, org.mockito.Mockito.atLeastOnce())
        .appendAndReturnId(logCaptor.capture(), any());

    logCaptor
        .getAllValues()
        .forEach(
            log -> {
              assertThat(log.getMetadata().has("actorId")).isFalse();
              assertThat(log.getMetadata().has("roles")).isFalse();
            });
  }

  @Test
  void identity_is_consistent_across_dispatched_and_completed_events() throws Exception {
    final String instanceId = "wf-consistency";
    final CaseInstance instance = mockInstanceWithIdentity("user-abc", "reviewer,approver");
    stubRegistry(instanceId, instance);

    when(orchestrator.submit(eq(instance), any(WorkRequest.class)))
        .thenReturn(
            CompletableFuture.completedStage(WorkResult.completed("k", Map.of("out", "val"), "w")));

    dispatch.dispatch(instanceId, "review").get();

    final ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository, org.mockito.Mockito.times(2))
        .appendAndReturnId(logCaptor.capture(), any());

    final List<EventLog> logs = logCaptor.getAllValues();
    assertThat(logs.get(0).getEventType()).isEqualTo(CaseHubEventType.WORKFLOW_STEP_DISPATCHED);
    assertThat(logs.get(1).getEventType()).isEqualTo(CaseHubEventType.WORKFLOW_STEP_COMPLETED);

    for (final EventLog log : logs) {
      assertThat(log.getMetadata().get("actorId").asText()).isEqualTo("user-abc");
      assertThat(log.getMetadata().get("roles").asText()).isEqualTo("reviewer,approver");
    }
  }

  @Test
  void identity_is_recorded_in_failed_event_metadata() throws Exception {
    final String instanceId = "wf-fail-id";
    final CaseInstance instance = mockInstanceWithIdentity("user-fail", "operator");
    stubRegistry(instanceId, instance);

    final CompletableFuture<WorkResult> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("downstream error"));
    when(orchestrator.submit(eq(instance), any(WorkRequest.class))).thenReturn(failed);

    dispatch.dispatch(instanceId, "transform").exceptionally(e -> null).get();

    final ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository, org.mockito.Mockito.times(2))
        .appendAndReturnId(logCaptor.capture(), any());

    final List<EventLog> logs = logCaptor.getAllValues();
    assertThat(logs.get(1).getEventType()).isEqualTo(CaseHubEventType.WORKFLOW_STEP_FAILED);
    assertThat(logs.get(1).getMetadata().get("actorId").asText()).isEqualTo("user-fail");
    assertThat(logs.get(1).getMetadata().get("roles").asText()).isEqualTo("operator");
  }

  @Test
  void partial_identity_records_only_available_attributes() throws Exception {
    final String instanceId = "wf-partial";
    final CaseInstance instance = mock(CaseInstance.class);
    UUID caseId = UUID.randomUUID();
    when(instance.getUuid()).thenReturn(caseId);
    when(instance.getPropagationContext())
        .thenReturn(PropagationContext.createRoot(Map.of("userId", "user-only")));
    when(caseInstanceCache.get(caseId)).thenReturn(instance);
    stubRegistry(instanceId, instance);

    when(orchestrator.submit(eq(instance), any(WorkRequest.class)))
        .thenReturn(CompletableFuture.completedStage(WorkResult.completed("k", Map.of(), "w")));

    dispatch.dispatch(instanceId, "cap").get();

    final ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository, org.mockito.Mockito.atLeastOnce())
        .appendAndReturnId(logCaptor.capture(), any());

    logCaptor
        .getAllValues()
        .forEach(
            log -> {
              assertThat(log.getMetadata().get("actorId").asText()).isEqualTo("user-only");
              assertThat(log.getMetadata().has("roles")).isFalse();
            });
  }

  @Test
  void propagation_context_without_identity_attributes_produces_no_identity_metadata()
      throws Exception {
    final String instanceId = "wf-no-attrs";
    final CaseInstance instance = mock(CaseInstance.class);
    UUID caseId = UUID.randomUUID();
    when(instance.getUuid()).thenReturn(caseId);
    when(instance.getPropagationContext())
        .thenReturn(PropagationContext.createRoot(Map.of("traceSource", "external")));
    when(caseInstanceCache.get(caseId)).thenReturn(instance);
    stubRegistry(instanceId, instance);

    when(orchestrator.submit(eq(instance), any(WorkRequest.class)))
        .thenReturn(CompletableFuture.completedStage(WorkResult.completed("k", Map.of(), "w")));

    dispatch.dispatch(instanceId, "cap").get();

    final ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository, org.mockito.Mockito.atLeastOnce())
        .appendAndReturnId(logCaptor.capture(), any());

    logCaptor
        .getAllValues()
        .forEach(
            log -> {
              assertThat(log.getMetadata().has("actorId")).isFalse();
              assertThat(log.getMetadata().has("roles")).isFalse();
              assertThat(log.getMetadata().has("capability")).isTrue();
            });
  }

  @Test
  void registered_handler_propagates_identity_through_dispatch_registry() throws Exception {
    final String instanceId = "wf-handler-id";
    final CaseInstance instance = mockInstanceWithIdentity("handler-user", "admin");
    stubRegistry(instanceId, instance);

    when(orchestrator.submit(eq(instance), any(WorkRequest.class)))
        .thenReturn(CompletableFuture.completedStage(WorkResult.completed("k", Map.of(), "w")));

    dispatchRegistry
        .get("casehub:dispatch")
        .dispatch(instanceId, Map.of("capability", "delegated-cap"))
        .get();

    final ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository, org.mockito.Mockito.atLeastOnce())
        .appendAndReturnId(logCaptor.capture(), any());

    logCaptor
        .getAllValues()
        .forEach(
            log -> {
              assertThat(log.getMetadata().get("actorId").asText()).isEqualTo("handler-user");
              assertThat(log.getMetadata().get("roles").asText()).isEqualTo("admin");
              assertThat(log.getMetadata().get("capability").asText()).isEqualTo("delegated-cap");
            });
  }

  @Test
  void identity_with_multiple_comma_separated_roles_preserved_verbatim() throws Exception {
    final String instanceId = "wf-multi-role";
    final String roles = "admin,analyst,reviewer,compliance-officer";
    final CaseInstance instance = mockInstanceWithIdentity("multi-role-user", roles);
    stubRegistry(instanceId, instance);

    when(orchestrator.submit(eq(instance), any(WorkRequest.class)))
        .thenReturn(CompletableFuture.completedStage(WorkResult.completed("k", Map.of(), "w")));

    dispatch.dispatch(instanceId, "cap").get();

    final ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository, org.mockito.Mockito.atLeastOnce())
        .appendAndReturnId(logCaptor.capture(), any());

    logCaptor
        .getAllValues()
        .forEach(log -> assertThat(log.getMetadata().get("roles").asText()).isEqualTo(roles));
  }

  // ---- helpers --------------------------------------------------------

  private CaseInstance mockInstance() {
    final CaseInstance instance = mock(CaseInstance.class);
    UUID caseId = UUID.randomUUID();
    when(instance.getUuid()).thenReturn(caseId);
    when(instance.getPropagationContext()).thenReturn(PropagationContext.createRoot());
    when(caseInstanceCache.get(caseId)).thenReturn(instance);
    return instance;
  }

  private CaseInstance mockInstanceWithIdentity(final String userId, final String roles) {
    final CaseInstance instance = mock(CaseInstance.class);
    UUID caseId = UUID.randomUUID();
    when(instance.getUuid()).thenReturn(caseId);
    when(instance.getPropagationContext())
        .thenReturn(PropagationContext.createRoot(Map.of("userId", userId, "roles", roles)));
    when(caseInstanceCache.get(caseId)).thenReturn(instance);
    return instance;
  }

  private void stubRegistry(final String instanceId, final CaseInstance instance) {
    UUID caseId = instance.getUuid();
    when(registry.get(instanceId))
        .thenReturn(new FlowExecution(caseId, "my-flow-worker", "hash-xyz"));
  }
}
