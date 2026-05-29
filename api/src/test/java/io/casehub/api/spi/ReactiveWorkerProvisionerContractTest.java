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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.WorkerContext;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReactiveWorkerProvisionerContractTest {

  @Test
  void interface_hasProvisionMethod() throws Exception {
    assertThat(
            ReactiveWorkerProvisioner.class.getMethod(
                "provision", Set.class, ProvisionContext.class))
        .isNotNull();
  }

  @Test
  void interface_hasTerminateMethod() throws Exception {
    assertThat(ReactiveWorkerProvisioner.class.getMethod("terminate", String.class)).isNotNull();
  }

  @Test
  void interface_hasGetCapabilitiesMethod() throws Exception {
    assertThat(ReactiveWorkerProvisioner.class.getMethod("getCapabilities")).isNotNull();
  }

  @Test
  void noOp_provision_uniFails_withProvisioningException() {
    ReactiveWorkerProvisioner provisioner = new NoOpStub();
    var ctx =
        new ProvisionContext(
            UUID.randomUUID(),
            "task",
            new WorkerContext(
                "t", null, null, List.of(), PropagationContext.createRoot(), Map.of()),
            PropagationContext.createRoot(),
            null,
            null);
    assertThatThrownBy(() -> provisioner.provision(Set.of("cap"), ctx).await().indefinitely())
        .isInstanceOf(ProvisioningException.class);
  }

  @Test
  void noOp_terminate_returnsCompletedUni() {
    ReactiveWorkerProvisioner provisioner = new NoOpStub();
    assertThatNoException()
        .isThrownBy(() -> provisioner.terminate("worker-1").await().indefinitely());
  }

  @Test
  void noOp_getCapabilities_returnsEmptySetUni() {
    ReactiveWorkerProvisioner provisioner = new NoOpStub();
    var caps = provisioner.getCapabilities().await().indefinitely();
    assertThat(caps).isEmpty();
  }

  static class NoOpStub implements ReactiveWorkerProvisioner {

    @Override
    public Uni<ProvisionResult> provision(Set<String> capabilities, ProvisionContext context) {
      return Uni.createFrom().failure(new ProvisioningException("NoOp"));
    }

    @Override
    public Uni<Void> terminate(String workerId) {
      return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Set<String>> getCapabilities() {
      return Uni.createFrom().item(Set.of());
    }
  }
}
