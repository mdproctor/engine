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
package io.casehub.engine.internal.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.CaseChannel;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.ProvisioningException;
import io.casehub.api.spi.ReactiveCaseChannelProvider;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.CapabilityHealth.CapabilityStatus;
import io.casehub.eidos.api.CapabilityHealth.ProbeContext;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for default no-op/empty SPI implementations. Instantiates the real classes (not stubs)
 * to verify actual behaviour.
 */
class DefaultWorkerSpiImplementationsTest {

  private static CurrentPrincipal stubPrincipal() {
    var p = mock(CurrentPrincipal.class);
    when(p.actorId()).thenReturn("test-user");
    when(p.roles()).thenReturn(Set.of("viewer"));
    return p;
  }

  // --- NoOpWorkerProvisioner ---

  @Test
  void noOpProvisioner_getCapabilities_returnsEmptySet() {
    var provisioner = new NoOpWorkerProvisioner();
    assertThat(provisioner.getCapabilities()).isEmpty();
  }

  @Test
  void noOpProvisioner_terminate_doesNotThrow() {
    var provisioner = new NoOpWorkerProvisioner();
    assertThatNoException().isThrownBy(() -> provisioner.terminate("any-worker-id", "tenant-1"));
  }

  @Test
  void noOpProvisioner_provision_throwsProvisioningException() {
    var provisioner = new NoOpWorkerProvisioner();
    var workerContext =
        new WorkerContext("desc", null, null, List.of(), PropagationContext.createRoot(), Map.of());
    var ctx =
        new ProvisionContext(
            UUID.randomUUID(),
            "tenant-1",
            "task",
            workerContext,
            PropagationContext.createRoot(),
            null,
            null);
    assertThatThrownBy(() -> provisioner.provision(java.util.Set.of("cap"), ctx))
        .isInstanceOf(ProvisioningException.class)
        .hasMessageContaining("WorkerProvisioner");
  }

  // --- NoOpWorkerStatusListener ---

  @Test
  void noOpStatusListener_onWorkerStarted_doesNotThrow() {
    var listener = new NoOpWorkerStatusListener();
    assertThatNoException()
        .isThrownBy(() -> listener.onWorkerStarted("worker-1", Map.of("session", "tmux-123")));
  }

  @Test
  void noOpStatusListener_onWorkerStarted_nullSessionMeta_doesNotThrow() {
    var listener = new NoOpWorkerStatusListener();
    assertThatNoException().isThrownBy(() -> listener.onWorkerStarted("worker-1", null));
  }

  @Test
  void noOpStatusListener_onWorkerCompleted_doesNotThrow() {
    var listener = new NoOpWorkerStatusListener();
    var result = WorkResult.completed("corr-1", Map.of(), "worker-1");
    assertThatNoException().isThrownBy(() -> listener.onWorkerCompleted("worker-1", result));
  }

  @Test
  void noOpStatusListener_onWorkerStalled_doesNotThrow() {
    var listener = new NoOpWorkerStatusListener();
    assertThatNoException().isThrownBy(() -> listener.onWorkerStalled("unknown-worker"));
  }

  // --- NoOpCaseChannelProvider ---

  @Test
  void noOpChannelProvider_openChannel_returnsNonNull() {
    var provider = new NoOpCaseChannelProvider();
    UUID caseId = UUID.randomUUID();
    CaseChannel ch = provider.openChannel(caseId, "coordination");
    assertThat(ch).isNotNull();
    assertThat(ch.backendType()).isEqualTo("none");
    assertThat(ch.purpose()).isEqualTo("coordination");
    assertThat(ch.id()).isNotBlank();
  }

  @Test
  void noOpChannelProvider_listChannels_returnsEmptyList() {
    var provider = new NoOpCaseChannelProvider();
    assertThat(provider.listChannels(UUID.randomUUID())).isEmpty();
  }

  @Test
  void noOpChannelProvider_postToChannel_doesNotThrow() {
    var provider = new NoOpCaseChannelProvider();
    CaseChannel ch = provider.openChannel(UUID.randomUUID(), "p");
    assertThatNoException().isThrownBy(() -> provider.postToChannel(ch, "alice", "hello"));
  }

  @Test
  void noOpChannelProvider_closeChannel_doesNotThrow() {
    var provider = new NoOpCaseChannelProvider();
    CaseChannel ch = provider.openChannel(UUID.randomUUID(), "p");
    assertThatNoException().isThrownBy(() -> provider.closeChannel(ch));
  }

  // --- EmptyWorkerContextProvider ---

  @Test
  void emptyContextProvider_buildContext_taskDescriptionMatchesCapability() {
    var provider = new EmptyWorkerContextProvider();
    provider.currentPrincipal = stubPrincipal();
    WorkerContext ctx =
        provider.buildContext("worker-1", null, WorkRequest.of("researcher", Map.of()));
    assertThat(ctx.taskDescription()).isEqualTo("researcher");
  }

  @Test
  void emptyContextProvider_buildContext_priorWorkersIsEmptyNotNull() {
    var provider = new EmptyWorkerContextProvider();
    provider.currentPrincipal = stubPrincipal();
    WorkerContext ctx = provider.buildContext("worker-1", null, WorkRequest.of("task", Map.of()));
    assertThat(ctx.priorWorkers()).isNotNull().isEmpty();
  }

  @Test
  void emptyContextProvider_buildContext_propagationContextIsNotNull() {
    var provider = new EmptyWorkerContextProvider();
    provider.currentPrincipal = stubPrincipal();
    WorkerContext ctx = provider.buildContext("worker-1", null, WorkRequest.of("task", Map.of()));
    assertThat(ctx.propagationContext()).isNotNull();
  }

  @Test
  void emptyContextProvider_buildContext_propagationContextCarriesIdentity() {
    var provider = new EmptyWorkerContextProvider();
    provider.currentPrincipal = stubPrincipal();
    WorkerContext ctx = provider.buildContext("worker-1", null, WorkRequest.of("task", Map.of()));
    assertThat(ctx.propagationContext().getAttribute("userId")).hasValue("test-user");
    assertThat(ctx.propagationContext().getAttribute("roles")).hasValue("viewer");
  }

  @Test
  void emptyContextProvider_buildContext_caseIdPopulated() {
    var provider = new EmptyWorkerContextProvider();
    provider.currentPrincipal = stubPrincipal();
    UUID caseId = UUID.randomUUID();
    provider.caseChannelProvider = mock(CaseChannelProvider.class);
    when(provider.caseChannelProvider.listChannels(caseId)).thenReturn(List.of());
    WorkerContext ctx = provider.buildContext("worker-1", caseId, WorkRequest.of("task", Map.of()));
    assertThat(ctx.caseId()).isEqualTo(caseId);
  }

  @Test
  void emptyContextProvider_buildContext_channelsPopulatedFromProvider() {
    UUID caseId = UUID.randomUUID();
    CaseChannel channel =
        new CaseChannel(caseId + "/coord", "coord", "coordination", "none", Map.of());
    CaseChannelProvider mockProvider = mock(CaseChannelProvider.class);
    when(mockProvider.listChannels(caseId)).thenReturn(List.of(channel));

    var provider = new EmptyWorkerContextProvider();
    provider.currentPrincipal = stubPrincipal();
    provider.caseChannelProvider = mockProvider;

    WorkerContext ctx = provider.buildContext("worker-1", caseId, WorkRequest.of("task", Map.of()));
    assertThat(ctx.channels()).containsExactly(channel);
  }

  @Test
  void emptyContextProvider_buildContext_nullCaseId_channelsEmpty() {
    var provider = new EmptyWorkerContextProvider();
    provider.currentPrincipal = stubPrincipal();
    WorkerContext ctx = provider.buildContext("worker-1", null, WorkRequest.of("task", Map.of()));
    assertThat(ctx.channels()).isEmpty();
  }

  // --- EmptyWorkerContextProvider (4-arg with parent PropagationContext) ---

  @Test
  void emptyContextProvider_buildContextWithParent_inheritsTraceId() {
    PropagationContext parent =
        PropagationContext.createRoot("parent-trace", Map.of("userId", "alice", "roles", "admin"));
    var provider = new EmptyWorkerContextProvider();
    WorkerContext ctx =
        provider.buildContext("worker-1", null, WorkRequest.of("task", Map.of()), parent);
    assertThat(ctx.propagationContext().getTraceId()).isEqualTo("parent-trace");
  }

  @Test
  void emptyContextProvider_buildContextWithParent_inheritsIdentityAttributes() {
    PropagationContext parent =
        PropagationContext.createRoot("t", Map.of("userId", "alice", "roles", "admin,viewer"));
    var provider = new EmptyWorkerContextProvider();
    WorkerContext ctx =
        provider.buildContext("worker-1", null, WorkRequest.of("task", Map.of()), parent);
    assertThat(ctx.propagationContext().getAttribute("userId")).hasValue("alice");
    assertThat(ctx.propagationContext().getAttribute("roles")).hasValue("admin,viewer");
  }

  @Test
  void emptyContextProvider_buildContextWithParent_inheritsBudget() {
    PropagationContext parent =
        PropagationContext.createRoot(Map.of("userId", "alice"), Duration.ofSeconds(30));
    var provider = new EmptyWorkerContextProvider();
    WorkerContext ctx =
        provider.buildContext("worker-1", null, WorkRequest.of("task", Map.of()), parent);
    assertThat(ctx.propagationContext().getDeadline()).isPresent();
    assertThat(ctx.propagationContext().getRemainingBudget()).isPresent();
  }

  @Test
  void emptyContextProvider_buildContextWithParent_channelsPopulated() {
    UUID caseId = UUID.randomUUID();
    CaseChannel channel = new CaseChannel(caseId + "/c", "c", "coordination", "none", Map.of());
    CaseChannelProvider mockProvider = mock(CaseChannelProvider.class);
    when(mockProvider.listChannels(caseId)).thenReturn(List.of(channel));

    PropagationContext parent = PropagationContext.createRoot("t", Map.of("userId", "alice"));
    var provider = new EmptyWorkerContextProvider();
    provider.caseChannelProvider = mockProvider;
    WorkerContext ctx =
        provider.buildContext("worker-1", caseId, WorkRequest.of("task", Map.of()), parent);
    assertThat(ctx.channels()).containsExactly(channel);
  }

  // --- NoOpReactiveWorkerProvisioner ---

  @Test
  void noOpReactiveProvisioner_provision_uniFails_withProvisioningException() {
    var provisioner = new NoOpReactiveWorkerProvisioner();
    var ctx =
        new ProvisionContext(
            UUID.randomUUID(),
            "tenant-1",
            "task",
            new WorkerContext(
                "desc", null, null, List.of(), PropagationContext.createRoot(), Map.of()),
            PropagationContext.createRoot(),
            null,
            null);
    assertThatThrownBy(() -> provisioner.provision(Set.of("cap"), ctx).await().indefinitely())
        .isInstanceOf(ProvisioningException.class);
  }

  @Test
  void noOpReactiveProvisioner_terminate_completesWithoutException() {
    var provisioner = new NoOpReactiveWorkerProvisioner();
    assertThatNoException()
        .isThrownBy(() -> provisioner.terminate("worker-1", "tenant-1").await().indefinitely());
  }

  @Test
  void noOpReactiveProvisioner_getCapabilities_returnsEmptySet() {
    var provisioner = new NoOpReactiveWorkerProvisioner();
    assertThat(provisioner.getCapabilities().await().indefinitely()).isEmpty();
  }

  // --- NoOpReactiveWorkerStatusListener ---

  @Test
  void noOpReactiveStatusListener_onWorkerStarted_completesWithoutException() {
    var listener = new NoOpReactiveWorkerStatusListener();
    assertThatNoException()
        .isThrownBy(() -> listener.onWorkerStarted("worker-1", Map.of()).await().indefinitely());
  }

  @Test
  void noOpReactiveStatusListener_onWorkerCompleted_completesWithoutException() {
    var listener = new NoOpReactiveWorkerStatusListener();
    var result = WorkResult.completed("corr-1", Map.of(), "worker-1");
    assertThatNoException()
        .isThrownBy(() -> listener.onWorkerCompleted("worker-1", result).await().indefinitely());
  }

  @Test
  void noOpReactiveStatusListener_onWorkerStalled_completesWithoutException() {
    var listener = new NoOpReactiveWorkerStatusListener();
    assertThatNoException()
        .isThrownBy(() -> listener.onWorkerStalled("unknown-worker").await().indefinitely());
  }

  // --- NoOpReactiveCaseChannelProvider ---

  @Test
  void noOpReactiveChannelProvider_openChannel_returnsSentinelWithBackendTypeNone() {
    var provider = new NoOpReactiveCaseChannelProvider();
    CaseChannel ch = provider.openChannel(UUID.randomUUID(), "coordination").await().indefinitely();
    assertThat(ch.backendType()).isEqualTo("none");
    assertThat(ch.purpose()).isEqualTo("coordination");
  }

  @Test
  void noOpReactiveChannelProvider_listChannels_returnsEmptyList() {
    var provider = new NoOpReactiveCaseChannelProvider();
    assertThat(provider.listChannels(UUID.randomUUID()).await().indefinitely()).isEmpty();
  }

  @Test
  void noOpReactiveChannelProvider_postToChannel_completesWithoutException() {
    var provider = new NoOpReactiveCaseChannelProvider();
    CaseChannel ch = provider.openChannel(UUID.randomUUID(), "p").await().indefinitely();
    assertThatNoException()
        .isThrownBy(() -> provider.postToChannel(ch, "alice", "hello").await().indefinitely());
  }

  // --- EmptyReactiveWorkerContextProvider ---

  @Test
  void emptyReactiveContextProvider_buildContext_taskDescriptionMatchesCapability() {
    var provider = new EmptyReactiveWorkerContextProvider();
    provider.currentPrincipal = stubPrincipal();
    WorkerContext ctx =
        provider
            .buildContext("worker-1", null, WorkRequest.of("researcher", Map.of()))
            .await()
            .indefinitely();
    assertThat(ctx.taskDescription()).isEqualTo("researcher");
  }

  @Test
  void emptyReactiveContextProvider_buildContext_priorWorkersIsEmpty() {
    var provider = new EmptyReactiveWorkerContextProvider();
    provider.currentPrincipal = stubPrincipal();
    WorkerContext ctx =
        provider
            .buildContext("worker-1", null, WorkRequest.of("task", Map.of()))
            .await()
            .indefinitely();
    assertThat(ctx.priorWorkers()).isNotNull().isEmpty();
  }

  @Test
  void emptyReactiveContextProvider_buildContext_propagationContextIsNotNull() {
    var provider = new EmptyReactiveWorkerContextProvider();
    provider.currentPrincipal = stubPrincipal();
    WorkerContext ctx =
        provider.buildContext("w", null, WorkRequest.of("task", Map.of())).await().indefinitely();
    assertThat(ctx.propagationContext()).isNotNull();
  }

  @Test
  void emptyReactiveContextProvider_buildContext_propagationContextCarriesIdentity() {
    var provider = new EmptyReactiveWorkerContextProvider();
    provider.currentPrincipal = stubPrincipal();
    WorkerContext ctx =
        provider.buildContext("w", null, WorkRequest.of("task", Map.of())).await().indefinitely();
    assertThat(ctx.propagationContext().getAttribute("userId")).hasValue("test-user");
    assertThat(ctx.propagationContext().getAttribute("roles")).hasValue("viewer");
  }

  @Test
  void emptyReactiveContextProvider_buildContext_caseIdPopulated() {
    UUID caseId = UUID.randomUUID();
    ReactiveCaseChannelProvider mockProvider = mock(ReactiveCaseChannelProvider.class);
    when(mockProvider.listChannels(caseId)).thenReturn(Uni.createFrom().item(List.of()));
    var provider = new EmptyReactiveWorkerContextProvider();
    provider.currentPrincipal = stubPrincipal();
    provider.reactiveCaseChannelProvider = mockProvider;
    WorkerContext ctx =
        provider
            .buildContext("worker-1", caseId, WorkRequest.of("task", Map.of()))
            .await()
            .indefinitely();
    assertThat(ctx.caseId()).isEqualTo(caseId);
  }

  @Test
  void emptyReactiveContextProvider_buildContext_channelsPopulatedFromProvider() {
    UUID caseId = UUID.randomUUID();
    CaseChannel channel =
        new CaseChannel(caseId + "/coord", "coord", "coordination", "none", Map.of());
    ReactiveCaseChannelProvider mockProvider = mock(ReactiveCaseChannelProvider.class);
    when(mockProvider.listChannels(caseId)).thenReturn(Uni.createFrom().item(List.of(channel)));

    var provider = new EmptyReactiveWorkerContextProvider();
    provider.currentPrincipal = stubPrincipal();
    provider.reactiveCaseChannelProvider = mockProvider;

    WorkerContext ctx =
        provider
            .buildContext("worker-1", caseId, WorkRequest.of("task", Map.of()))
            .await()
            .indefinitely();
    assertThat(ctx.channels()).containsExactly(channel);
  }

  // --- NoOpCapabilityHealth ---

  @Test
  void noOpCapabilityHealth_probe_returnsReady() {
    var health = new NoOpCapabilityHealth();
    var descriptor =
        new AgentDescriptor(
            "agent-1",
            "Test",
            "1.0",
            "openai",
            "gpt-4",
            "4-turbo",
            null,
            null,
            null,
            null,
            null,
            "review",
            List.of(),
            null,
            null,
            null,
            "casehubio",
            null);
    CapabilityStatus status = health.probe(descriptor, "code-review", ProbeContext.of(null));
    assertThat(status).isInstanceOf(CapabilityStatus.Ready.class);
  }
}
