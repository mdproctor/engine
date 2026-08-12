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
package io.casehub.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.casehub.api.acl.EngineWorkerActions;
import java.util.List;
import org.junit.jupiter.api.Test;

class BindingPermissionIntentTest {

  @Test
  void permissionIntent_setAndGet() {
    var binding =
        Binding.builder()
            .name("b1")
            .capability(io.casehub.worker.api.Capability.of("cap1", ".", "."))
            .on(new ContextChangeTrigger(".ready"))
            .permissionIntent(
                List.of(EngineWorkerActions.READ_CONTEXT, EngineWorkerActions.SIGNAL_CASE))
            .build();

    assertEquals(
        List.of(EngineWorkerActions.READ_CONTEXT, EngineWorkerActions.SIGNAL_CASE),
        binding.getPermissionIntent());
  }

  @Test
  void permissionIntent_defaultsNull() {
    var binding =
        Binding.builder()
            .name("b1")
            .capability(io.casehub.worker.api.Capability.of("cap1", ".", "."))
            .on(new ContextChangeTrigger(".ready"))
            .build();

    assertNull(binding.getPermissionIntent());
  }
}
