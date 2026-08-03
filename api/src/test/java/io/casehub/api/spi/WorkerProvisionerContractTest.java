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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerProvisionerContractTest {

  @Test
  void interface_hasProvisionMethod() throws Exception {
    assertThat(WorkerProvisioner.class.getMethod("provision", Set.class, ProvisionContext.class))
        .isNotNull();
  }

  @Test
  void interface_hasTerminateMethod() throws Exception {
    assertThat(WorkerProvisioner.class.getMethod("terminate", String.class, String.class))
        .isNotNull();
  }

  @Test
  void interface_hasGetCapabilitiesMethod() throws Exception {
    assertThat(WorkerProvisioner.class.getMethod("getCapabilities")).isNotNull();
  }

  @Test
  void noOp_getCapabilities_returnsEmptySet() {
    WorkerProvisioner noOp = new NoOpStub();
    assertThat(noOp.getCapabilities()).isEmpty();
  }

  @Test
  void noOp_terminate_unknownWorkerId_isNoOp() {
    WorkerProvisioner noOp = new NoOpStub();
    assertThatNoException().isThrownBy(() -> noOp.terminate("unknown-worker-id", "tenant-1"));
  }

  @Test
  void noOp_provision_throwsProvisioningException() {
    WorkerProvisioner noOp = new NoOpStub();
    var ctx =
        new ProvisionContext(
            UUID.randomUUID(),
            "tenant-1",
            "researcher",
            new WorkerContext(
                "task", null, null, List.of(), PropagationContext.createRoot(), Map.of()),
            PropagationContext.createRoot(),
            null,
            null,
            null);
    assertThatThrownBy(() -> noOp.provision(Set.of("research"), ctx))
        .isInstanceOf(ProvisioningException.class);
  }

  @Test
  void provisioningException_isUnchecked() {
    assertThat(new ProvisioningException("x")).isInstanceOf(RuntimeException.class);
  }

  static class NoOpStub implements WorkerProvisioner {
    @Override
    public ProvisionResult provision(Set<String> capabilities, ProvisionContext context) {
      throw new ProvisioningException("NoOp — no provisioner configured");
    }

    @Override
    public void terminate(String workerId, String tenancyId) {}

    @Override
    public Set<String> getCapabilities() {
      return Set.of();
    }
  }
}
