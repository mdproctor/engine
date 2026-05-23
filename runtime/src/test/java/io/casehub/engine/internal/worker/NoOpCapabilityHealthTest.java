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

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.CapabilityHealth.CapabilityStatus;
import io.casehub.eidos.api.CapabilityHealth.ProbeContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoOpCapabilityHealthTest {

  @Test
  void probe_alwaysReturnsReady() {
    NoOpCapabilityHealth health = new NoOpCapabilityHealth();
    AgentDescriptor descriptor =
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
            "review",
            List.of(),
            null,
            null,
            null,
            "casehubio");

    CapabilityStatus status = health.probe(descriptor, "code-review", ProbeContext.of(null));

    assertThat(status).isInstanceOf(CapabilityStatus.Ready.class);
  }
}
