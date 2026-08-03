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
package io.casehub.api.model.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.AclResourceType;
import org.junit.jupiter.api.Test;

class WorkerActionTest {

  @Test
  void allActionsMaptoCaseResourceType() {
    for (WorkerAction action : WorkerAction.values()) {
      AclGrant grant = action.toAclGrant();
      assertEquals(
          AclResourceType.CASE,
          grant.resourceType(),
          "Action " + action + " should map to CASE resource type");
    }
  }

  @Test
  void readContextMapsToRead() {
    assertEquals(AclAction.READ, WorkerAction.READ_CONTEXT.toAclGrant().action());
  }

  @Test
  void writeContextMapsToWrite() {
    assertEquals(AclAction.WRITE, WorkerAction.WRITE_CONTEXT.toAclGrant().action());
  }

  @Test
  void signalCaseMapsToWrite() {
    assertEquals(AclAction.WRITE, WorkerAction.SIGNAL_CASE.toAclGrant().action());
  }

  @Test
  void adminMapsToAdmin() {
    assertEquals(AclAction.ADMIN, WorkerAction.ADMIN.toAclGrant().action());
  }

  @Test
  void readEventLogMapsToRead() {
    assertEquals(AclAction.READ, WorkerAction.READ_EVENT_LOG.toAclGrant().action());
  }

  @Test
  void readPlanItemsMapsToRead() {
    assertEquals(AclAction.READ, WorkerAction.READ_PLAN_ITEMS.toAclGrant().action());
  }

  @Test
  void spawnSubCaseMapsToWrite() {
    assertEquals(AclAction.WRITE, WorkerAction.SPAWN_SUB_CASE.toAclGrant().action());
  }
}
