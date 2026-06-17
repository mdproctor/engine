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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.eidos.api.AgentDescriptor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerTest {

  private static final AgentDescriptor DESCRIPTOR =
      new AgentDescriptor(
          "agent-1",
          "TestAgent",
          "1.0",
          "openai",
          "gpt-4",
          "4-turbo",
          null,
          null,
          null,
          null,
          null,
          "code-review",
          List.of(),
          null,
          null,
          null,
          "casehubio",
          null);

  @Test
  void hasDescriptor_withDescriptor_returnsTrue() {
    Worker worker =
        Worker.builder()
            .name("agent-worker")
            .capabilities(
                Capability.builder().name("review").inputSchema(".x").outputSchema(".y").build())
            .agentDescriptor(DESCRIPTOR)
            .function(input -> WorkerResult.of(Map.of()))
            .build();

    assertThat(worker.hasDescriptor()).isTrue();
  }

  @Test
  void hasDescriptor_withoutDescriptor_returnsFalse() {
    Worker worker =
        Worker.builder()
            .name("plain-worker")
            .capabilities(
                Capability.builder().name("review").inputSchema(".x").outputSchema(".y").build())
            .function(input -> WorkerResult.of(Map.of()))
            .build();

    assertThat(worker.hasDescriptor()).isFalse();
  }

  @Test
  void agentDescriptor_accessor_returnsStoredDescriptor() {
    Worker worker =
        Worker.builder()
            .name("agent-worker")
            .capabilities(
                Capability.builder().name("review").inputSchema(".x").outputSchema(".y").build())
            .agentDescriptor(DESCRIPTOR)
            .function(input -> WorkerResult.of(Map.of()))
            .build();

    assertThat(worker.agentDescriptor()).isSameAs(DESCRIPTOR);
  }

  @Test
  void agentDescriptor_notSet_returnsNull() {
    Worker worker =
        Worker.builder()
            .name("plain-worker")
            .capabilities(
                Capability.builder().name("review").inputSchema(".x").outputSchema(".y").build())
            .function(input -> WorkerResult.of(Map.of()))
            .build();

    assertThat(worker.agentDescriptor()).isNull();
  }
}
