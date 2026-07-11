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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.casehub.worker.api.Worker;
import org.junit.jupiter.api.Test;

class ExecutorRefTest {

  @Test
  void ofWithNameOnly() {
    ExecutorRef ref = ExecutorRef.of("worker-1");
    assertEquals("worker-1", ref.name());
    assertNull(ref.description());
  }

  @Test
  void ofWithNameAndDescription() {
    ExecutorRef ref = ExecutorRef.of("worker-1", "does things");
    assertEquals("worker-1", ref.name());
    assertEquals("does things", ref.description());
  }

  @Test
  void ofRejectsNullName() {
    assertThrows(NullPointerException.class, () -> ExecutorRef.of(null));
  }

  @Test
  void fromWorkerDelegates() {
    Worker worker =
        Worker.builder().name("test-worker").capabilityName("cap-1").noFunction().build();
    ExecutorRef ref = ExecutorRef.fromWorker(worker);
    assertEquals("test-worker", ref.name());
    assertNull(ref.description());
  }

  @Test
  void fromWorkerWithDescription() {
    Worker worker =
        Worker.builder().name("test-worker").capabilityName("cap-1").noFunction().build();
    // Worker record does not have a builder method for description in the
    // current worker-api jar, so we test the factory with the name adapter
    ExecutorRef ref = ExecutorRef.of(worker.name(), "manual description");
    assertEquals("test-worker", ref.name());
    assertEquals("manual description", ref.description());
  }

  @Test
  void equalityBasedOnNameAndDescription() {
    ExecutorRef a = ExecutorRef.of("x", "desc");
    ExecutorRef b = ExecutorRef.of("x", "desc");
    assertEquals(a, b);
  }

  @Test
  void inequalityOnDifferentName() {
    ExecutorRef a = ExecutorRef.of("x");
    ExecutorRef b = ExecutorRef.of("y");
    assertNotEquals(a, b);
  }
}
