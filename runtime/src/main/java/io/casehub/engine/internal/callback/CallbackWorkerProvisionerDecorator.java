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

import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.WorkerProvisioner;
import io.casehub.platform.api.callback.CallbackRegistration;
import io.casehub.platform.api.callback.CallbackRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.callback.CallbackInvoker;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * CDI decorator that routes {@link WorkerProvisioner} calls to remote callback registrations when
 * present. Falls through to the wrapped delegate (local implementation or @DefaultBean no-op) when
 * no callbacks are registered.
 */
@Decorator
@Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION + 100)
public class CallbackWorkerProvisionerDecorator implements WorkerProvisioner {

  private static final Logger LOG = Logger.getLogger(CallbackWorkerProvisionerDecorator.class);
  private static final String SPI_NAME = "worker-provisioner";

  private final WorkerProvisioner delegate;
  private final CallbackRegistry callbackRegistry;
  private final CallbackInvoker callbackInvoker;
  private final CurrentPrincipal currentPrincipal;

  @Inject
  CallbackWorkerProvisionerDecorator(
      @Delegate final WorkerProvisioner delegate,
      final CallbackRegistry callbackRegistry,
      final CallbackInvoker callbackInvoker,
      final CurrentPrincipal currentPrincipal) {
    this.delegate = delegate;
    this.callbackRegistry = callbackRegistry;
    this.callbackInvoker = callbackInvoker;
    this.currentPrincipal = currentPrincipal;
  }

  @Override
  public ProvisionResult provision(final Set<String> capabilities, final ProvisionContext context) {
    final String tenancyId = currentPrincipal.tenancyId();
    final List<CallbackRegistration> registrations =
        callbackRegistry.findBySpi(SPI_NAME, tenancyId);

    if (registrations.isEmpty()) {
      return delegate.provision(capabilities, context);
    }

    for (final CallbackRegistration reg : registrations) {
      final ProvisionResult result =
          callbackInvoker.invoke(
              reg, "provision", new Object[] {capabilities, context}, ProvisionResult.class);
      if (result != null) {
        return result;
      }
    }
    return delegate.provision(capabilities, context);
  }

  @Override
  public void terminate(final String workerId, final String tenancyId) {
    final String currentTenancyId = currentPrincipal.tenancyId();
    final List<CallbackRegistration> registrations =
        callbackRegistry.findBySpi(SPI_NAME, currentTenancyId);

    if (registrations.isEmpty()) {
      delegate.terminate(workerId, tenancyId);
      return;
    }

    for (final CallbackRegistration reg : registrations) {
      callbackInvoker.invoke(reg, "terminate", new Object[] {workerId, tenancyId}, void.class);
    }
  }

  @Override
  public Set<String> getCapabilities() {
    final String tenancyId = currentPrincipal.tenancyId();
    final List<CallbackRegistration> registrations =
        callbackRegistry.findBySpi(SPI_NAME, tenancyId);

    if (registrations.isEmpty()) {
      return delegate.getCapabilities();
    }

    for (final CallbackRegistration reg : registrations) {
      @SuppressWarnings("unchecked")
      final Set<String> result =
          callbackInvoker.invoke(reg, "getCapabilities", new Object[] {}, Set.class);
      if (result != null) {
        return result;
      }
    }
    return delegate.getCapabilities();
  }
}
