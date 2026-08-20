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
package io.casehub.engine.common.spi.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompoundLockRegistryTest {

  private CompoundLockRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new CompoundLockRegistry();
  }

  @Test
  void getLockReturnsSameInstanceForSameKey() {
    UUID caseId = UUID.randomUUID();
    ReentrantLock lock1 = registry.getLock(caseId, "compound-a");
    ReentrantLock lock2 = registry.getLock(caseId, "compound-a");
    assertThat(lock1).isSameAs(lock2);
  }

  @Test
  void getLockReturnsDifferentInstancesForDifferentKeys() {
    UUID caseId = UUID.randomUUID();
    ReentrantLock lockA = registry.getLock(caseId, "compound-a");
    ReentrantLock lockB = registry.getLock(caseId, "compound-b");
    assertThat(lockA).isNotSameAs(lockB);
  }

  @Test
  void getLockReturnsDifferentInstancesForDifferentCases() {
    UUID case1 = UUID.randomUUID();
    UUID case2 = UUID.randomUUID();
    ReentrantLock lock1 = registry.getLock(case1, "compound-a");
    ReentrantLock lock2 = registry.getLock(case2, "compound-a");
    assertThat(lock1).isNotSameAs(lock2);
  }

  @Test
  void cleanForCaseRemovesAllLocksForCase() {
    UUID caseId = UUID.randomUUID();
    ReentrantLock lockBefore = registry.getLock(caseId, "compound-a");
    registry.cleanForCase(caseId);
    ReentrantLock lockAfter = registry.getLock(caseId, "compound-a");
    assertThat(lockAfter).isNotSameAs(lockBefore);
  }

  @Test
  void cleanForCaseDoesNotAffectOtherCases() {
    UUID case1 = UUID.randomUUID();
    UUID case2 = UUID.randomUUID();
    ReentrantLock lock2 = registry.getLock(case2, "compound-a");
    registry.cleanForCase(case1);
    assertThat(registry.getLock(case2, "compound-a")).isSameAs(lock2);
  }

  @Test
  void cleanForCompoundRemovesSingleLock() {
    UUID caseId = UUID.randomUUID();
    ReentrantLock lockA = registry.getLock(caseId, "compound-a");
    ReentrantLock lockB = registry.getLock(caseId, "compound-b");
    registry.cleanForCompound(caseId, "compound-a");
    assertThat(registry.getLock(caseId, "compound-a")).isNotSameAs(lockA);
    assertThat(registry.getLock(caseId, "compound-b")).isSameAs(lockB);
  }
}
