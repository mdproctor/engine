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
package io.casehub.engine.internal.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.WorkerProvisioner;
import io.casehub.platform.api.callback.CallbackRegistration;
import io.casehub.platform.api.callback.CallbackRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.callback.CallbackInvoker;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CallbackWorkerProvisionerDecoratorTest {

  private WorkerProvisioner delegate;
  private CallbackRegistry callbackRegistry;
  private CallbackInvoker callbackInvoker;
  private CurrentPrincipal currentPrincipal;
  private CallbackWorkerProvisionerDecorator decorator;

  private static final String TENANT_ID = "tenant-1";
  private static final String SPI_NAME = "worker-provisioner";

  @BeforeEach
  void setUp() {
    delegate = mock(WorkerProvisioner.class);
    callbackRegistry = mock(CallbackRegistry.class);
    callbackInvoker = mock(CallbackInvoker.class);
    currentPrincipal = mock(CurrentPrincipal.class);
    when(currentPrincipal.tenancyId()).thenReturn(TENANT_ID);

    decorator =
        new CallbackWorkerProvisionerDecorator(
            delegate, callbackRegistry, callbackInvoker, currentPrincipal);
  }

  @Test
  void provision_noRegistrations_delegatesToLocal() {
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of());
    final ProvisionResult expected = new ProvisionResult(UUID.randomUUID(), "worker-1");
    when(delegate.provision(any(), any())).thenReturn(expected);

    final ProvisionResult result = decorator.provision(Set.of("analyse"), testContext());

    assertThat(result).isSameAs(expected);
    verify(callbackInvoker, never()).invoke(any(), any(), any(), any());
  }

  @Test
  void provision_withRegistrations_invokesRemote() {
    final CallbackRegistration reg = registration("http://app1/callback");
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of(reg));

    final ProvisionResult remoteResult = new ProvisionResult(UUID.randomUUID(), "remote-worker");
    when(callbackInvoker.invoke(eq(reg), eq("provision"), any(), eq(ProvisionResult.class)))
        .thenReturn(remoteResult);

    final ProvisionResult result = decorator.provision(Set.of("analyse"), testContext());

    assertThat(result).isSameAs(remoteResult);
    verify(delegate, never()).provision(any(), any());
  }

  @Test
  void provision_multipleRegistrations_returnsFirstNonNull() {
    final CallbackRegistration reg1 = registration("http://app1/callback");
    final CallbackRegistration reg2 = registration("http://app2/callback");
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of(reg1, reg2));

    when(callbackInvoker.invoke(eq(reg1), eq("provision"), any(), eq(ProvisionResult.class)))
        .thenReturn(null);
    final ProvisionResult secondResult = new ProvisionResult(UUID.randomUUID(), "worker-2");
    when(callbackInvoker.invoke(eq(reg2), eq("provision"), any(), eq(ProvisionResult.class)))
        .thenReturn(secondResult);

    final ProvisionResult result = decorator.provision(Set.of("analyse"), testContext());

    assertThat(result).isSameAs(secondResult);
  }

  @Test
  void provision_allRemoteReturnNull_fallsBackToDelegate() {
    final CallbackRegistration reg = registration("http://app1/callback");
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of(reg));
    when(callbackInvoker.invoke(eq(reg), eq("provision"), any(), eq(ProvisionResult.class)))
        .thenReturn(null);

    final ProvisionResult localResult = new ProvisionResult(null, "local-worker");
    when(delegate.provision(any(), any())).thenReturn(localResult);

    final ProvisionResult result = decorator.provision(Set.of("analyse"), testContext());

    assertThat(result).isSameAs(localResult);
  }

  @Test
  void terminate_noRegistrations_delegatesToLocal() {
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of());

    decorator.terminate("worker-1", TENANT_ID);

    verify(delegate).terminate("worker-1", TENANT_ID);
    verify(callbackInvoker, never()).invoke(any(), any(), any(), any());
  }

  @Test
  void terminate_withRegistrations_invokesAllRemotes() {
    final CallbackRegistration reg1 = registration("http://app1/callback");
    final CallbackRegistration reg2 = registration("http://app2/callback");
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of(reg1, reg2));

    decorator.terminate("worker-1", TENANT_ID);

    verify(callbackInvoker).invoke(eq(reg1), eq("terminate"), any(), eq(void.class));
    verify(callbackInvoker).invoke(eq(reg2), eq("terminate"), any(), eq(void.class));
    verify(delegate, never()).terminate(any(), any());
  }

  @Test
  void getCapabilities_noRegistrations_delegatesToLocal() {
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of());
    when(delegate.getCapabilities()).thenReturn(Set.of("local-cap"));

    final Set<String> result = decorator.getCapabilities();

    assertThat(result).containsExactly("local-cap");
  }

  @Test
  void getCapabilities_withRegistrations_invokesRemote() {
    final CallbackRegistration reg = registration("http://app1/callback");
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of(reg));
    when(callbackInvoker.invoke(eq(reg), eq("getCapabilities"), any(), eq(Set.class)))
        .thenReturn(Set.of("remote-cap"));

    final Set<String> result = decorator.getCapabilities();

    assertThat(result).containsExactly("remote-cap");
  }

  private static ProvisionContext testContext() {
    return new ProvisionContext(
        UUID.randomUUID(), TENANT_ID, "analyse", null, null, null, null, null);
  }

  private static CallbackRegistration registration(final String url) {
    return new CallbackRegistration(
        UUID.randomUUID().toString(),
        SPI_NAME,
        url,
        "cred-ref",
        TENANT_ID,
        5000,
        Map.of(),
        Instant.now(),
        Instant.now().plusSeconds(300),
        Instant.now());
  }
}
