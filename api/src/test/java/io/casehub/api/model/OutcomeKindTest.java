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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.worker.api.WorkerOutcome;
import org.junit.jupiter.api.Test;

class OutcomeKindTest {

  @Test
  void successIsNotTerminal() {
    assertFalse(OutcomeKind.SUCCESS.isTerminal());
  }

  @Test
  void nonSuccessAreTerminal() {
    assertTrue(OutcomeKind.DECLINED.isTerminal());
    assertTrue(OutcomeKind.FAILED.isTerminal());
    assertTrue(OutcomeKind.EXPIRED.isTerminal());
    assertTrue(OutcomeKind.ESCALATED.isTerminal());
  }

  @Test
  void fromWorkerOutcomeSuccess() {
    assertEquals(
        OutcomeKind.SUCCESS, OutcomeKind.fromWorkerOutcome(new WorkerOutcome.Success(null)));
  }

  @Test
  void fromWorkerOutcomeDeclined() {
    assertEquals(
        OutcomeKind.DECLINED, OutcomeKind.fromWorkerOutcome(new WorkerOutcome.Declined("reason")));
  }

  @Test
  void fromWorkerOutcomeFailed() {
    assertEquals(
        OutcomeKind.FAILED, OutcomeKind.fromWorkerOutcome(new WorkerOutcome.Failed("reason")));
  }

  @Test
  void fromWorkerOutcomeExpired() {
    assertEquals(
        OutcomeKind.EXPIRED, OutcomeKind.fromWorkerOutcome(new WorkerOutcome.Expired("reason")));
  }

  @Test
  void allValuesPresent() {
    assertEquals(6, OutcomeKind.values().length);
    assertNotNull(OutcomeKind.valueOf("SUCCESS"));
    assertNotNull(OutcomeKind.valueOf("DECLINED"));
    assertNotNull(OutcomeKind.valueOf("FAILED"));
    assertNotNull(OutcomeKind.valueOf("EXPIRED"));
    assertNotNull(OutcomeKind.valueOf("ESCALATED"));
    assertNotNull(OutcomeKind.valueOf("COMPLETED"));
  }
}
