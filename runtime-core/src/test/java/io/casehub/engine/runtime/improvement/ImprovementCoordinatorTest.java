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
package io.casehub.engine.internal.improvement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImprovementCoordinatorTest {

  private static final String TENANT = "test-tenant";
  private ImprovementCoordinator coordinator;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    coordinator = new ImprovementCoordinator(new InMemoryImprovementBlockStore());
    caseId = UUID.randomUUID();
  }

  @Test
  void blockAndCheckBlocked() {
    var improvementId = UUID.randomUUID();
    var blockerId = UUID.randomUUID();
    coordinator.block(caseId, improvementId, blockerId, TENANT);

    assertThat(coordinator.isBlocked(caseId, improvementId, TENANT)).isTrue();
  }

  @Test
  void unblockReleasesBlock() {
    var improvementId = UUID.randomUUID();
    var blockerId = UUID.randomUUID();
    coordinator.block(caseId, improvementId, blockerId, TENANT);
    coordinator.unblock(caseId, improvementId, TENANT);

    assertThat(coordinator.isBlocked(caseId, improvementId, TENANT)).isFalse();
  }

  @Test
  void unblockedByDefault() {
    assertThat(coordinator.isBlocked(caseId, UUID.randomUUID(), TENANT)).isFalse();
  }

  @Test
  void blockedByReturnsBlocker() {
    var improvementId = UUID.randomUUID();
    var blockerId = UUID.randomUUID();
    coordinator.block(caseId, improvementId, blockerId, TENANT);

    assertThat(coordinator.blockedBy(caseId, improvementId, TENANT)).isEqualTo(blockerId);
  }

  @Test
  void blockedByReturnsNullWhenNotBlocked() {
    assertThat(coordinator.blockedBy(caseId, UUID.randomUUID(), TENANT)).isNull();
  }

  @Test
  void perCaseIsolation() {
    var case2 = UUID.randomUUID();
    var improvementId = UUID.randomUUID();
    var blockerId = UUID.randomUUID();
    coordinator.block(caseId, improvementId, blockerId, TENANT);

    assertThat(coordinator.isBlocked(caseId, improvementId, TENANT)).isTrue();
    assertThat(coordinator.isBlocked(case2, improvementId, TENANT)).isFalse();
  }
}
