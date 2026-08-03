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

import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.AclResourceType;

public enum WorkerAction {
  READ_CONTEXT(AclAction.READ),
  WRITE_CONTEXT(AclAction.WRITE),
  SIGNAL_CASE(AclAction.WRITE),
  READ_EVENT_LOG(AclAction.READ),
  READ_PLAN_ITEMS(AclAction.READ),
  SPAWN_SUB_CASE(AclAction.WRITE),
  ADMIN(AclAction.ADMIN);

  private final AclAction aclAction;

  WorkerAction(AclAction aclAction) {
    this.aclAction = aclAction;
  }

  public AclGrant toAclGrant() {
    return new AclGrant(aclAction, AclResourceType.CASE);
  }

  public AclAction aclAction() {
    return aclAction;
  }
}
