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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkerServiceAccountIdTest {

  @Test
  void workerServiceAccountId_setAndGet() {
    var def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .workerServiceAccountId("my-worker", "agent:pool-1@acme.io")
            .build();

    assertEquals("agent:pool-1@acme.io", def.getWorkerServiceAccountId("my-worker"));
  }

  @Test
  void workerServiceAccountId_missingWorker_returnsNull() {
    var def = CaseDefinition.builder().namespace("ns").name("test").version("1.0").build();

    assertNull(def.getWorkerServiceAccountId("nonexistent"));
  }

  @Test
  void workerServiceAccountId_rejectsHumanIdentity() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CaseDefinition.builder()
                .namespace("ns")
                .name("test")
                .version("1.0")
                .workerServiceAccountId("my-worker", "mark@acme.io")
                .build());
  }

  @Test
  void workerServiceAccountId_rejectsSystemIdentity() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CaseDefinition.builder()
                .namespace("ns")
                .name("test")
                .version("1.0")
                .workerServiceAccountId("my-worker", "system:internal")
                .build());
  }

  @Test
  void workerServiceAccountId_acceptsAgentPrefix() {
    var def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .workerServiceAccountId("my-worker", "agent:claudony-1")
            .build();

    assertEquals("agent:claudony-1", def.getWorkerServiceAccountId("my-worker"));
  }

  @Test
  void workerServiceAccountId_acceptsAgentPatternFormat() {
    var def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .workerServiceAccountId("my-worker", "claudony-pool:risk@acme.io")
            .build();

    assertEquals("claudony-pool:risk@acme.io", def.getWorkerServiceAccountId("my-worker"));
  }
}
