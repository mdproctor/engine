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
package io.casehub.api.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.platform.api.acl.AclAction;
import org.junit.jupiter.api.Test;

class EngineWorkerActionsTest {

  @Test
  void allConstantsHaveCorrectAclAction() {
    assertThat(EngineWorkerActions.READ_CONTEXT.aclAction()).isEqualTo(AclAction.READ);
    assertThat(EngineWorkerActions.WRITE_CONTEXT.aclAction()).isEqualTo(AclAction.WRITE);
    assertThat(EngineWorkerActions.SIGNAL_CASE.aclAction()).isEqualTo(AclAction.WRITE);
    assertThat(EngineWorkerActions.READ_EVENT_LOG.aclAction()).isEqualTo(AclAction.READ);
    assertThat(EngineWorkerActions.READ_PLAN_ITEMS.aclAction()).isEqualTo(AclAction.READ);
    assertThat(EngineWorkerActions.SPAWN_SUB_CASE.aclAction()).isEqualTo(AclAction.WRITE);
    assertThat(EngineWorkerActions.CLAIM_WORK_ITEM.aclAction()).isEqualTo(AclAction.CLAIM);
    assertThat(EngineWorkerActions.ADMIN.aclAction()).isEqualTo(AclAction.ADMIN);
  }

  @Test
  void fromKebabCase_allActions() {
    assertThat(EngineWorkerActions.fromKebabCase("read-context").name()).isEqualTo("READ_CONTEXT");
    assertThat(EngineWorkerActions.fromKebabCase("write-context").name())
        .isEqualTo("WRITE_CONTEXT");
    assertThat(EngineWorkerActions.fromKebabCase("signal-case").name()).isEqualTo("SIGNAL_CASE");
    assertThat(EngineWorkerActions.fromKebabCase("read-event-log").name())
        .isEqualTo("READ_EVENT_LOG");
    assertThat(EngineWorkerActions.fromKebabCase("read-plan-items").name())
        .isEqualTo("READ_PLAN_ITEMS");
    assertThat(EngineWorkerActions.fromKebabCase("spawn-sub-case").name())
        .isEqualTo("SPAWN_SUB_CASE");
    assertThat(EngineWorkerActions.fromKebabCase("claim-work-item").name())
        .isEqualTo("CLAIM_WORK_ITEM");
    assertThat(EngineWorkerActions.fromKebabCase("admin").name()).isEqualTo("ADMIN");
  }

  @Test
  void fromKebabCase_unknownName_throws() {
    assertThatThrownBy(() -> EngineWorkerActions.fromKebabCase("unknown-action"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown-action");
  }

  @Test
  void fromKebabCase_null_throws() {
    assertThatThrownBy(() -> EngineWorkerActions.fromKebabCase(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
