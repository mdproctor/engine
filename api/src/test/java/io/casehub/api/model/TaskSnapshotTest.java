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

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TaskSnapshotTest {

  @Test
  void fieldsPreserved() {
    Instant now = Instant.now();
    TaskSnapshot snap =
        new TaskSnapshot("id-1", "do work", "worker-a", "desc", TaskStatus.RUNNING, now);
    assertThat(snap.id()).isEqualTo("id-1");
    assertThat(snap.description()).isEqualTo("do work");
    assertThat(snap.executorName()).isEqualTo("worker-a");
    assertThat(snap.executorDescription()).isEqualTo("desc");
    assertThat(snap.status()).isEqualTo(TaskStatus.RUNNING);
    assertThat(snap.createdAt()).isEqualTo(now);
  }

  @Test
  void nullableFieldsAllowed() {
    TaskSnapshot snap =
        new TaskSnapshot("id-1", null, null, null, TaskStatus.PENDING, Instant.now());
    assertThat(snap.description()).isNull();
    assertThat(snap.executorName()).isNull();
    assertThat(snap.executorDescription()).isNull();
  }
}
