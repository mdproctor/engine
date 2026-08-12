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

import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.WorkerAction;
import java.util.Map;

public final class EngineWorkerActions {

  public static final WorkerAction READ_CONTEXT = new WorkerAction("READ_CONTEXT", AclAction.READ);
  public static final WorkerAction WRITE_CONTEXT =
      new WorkerAction("WRITE_CONTEXT", AclAction.WRITE);
  public static final WorkerAction SIGNAL_CASE = new WorkerAction("SIGNAL_CASE", AclAction.WRITE);
  public static final WorkerAction READ_EVENT_LOG =
      new WorkerAction("READ_EVENT_LOG", AclAction.READ);
  public static final WorkerAction READ_PLAN_ITEMS =
      new WorkerAction("READ_PLAN_ITEMS", AclAction.READ);
  public static final WorkerAction SPAWN_SUB_CASE =
      new WorkerAction("SPAWN_SUB_CASE", AclAction.WRITE);
  public static final WorkerAction CLAIM_WORK_ITEM =
      new WorkerAction("CLAIM_WORK_ITEM", AclAction.CLAIM);
  public static final WorkerAction ADMIN = new WorkerAction("ADMIN", AclAction.ADMIN);

  private static final Map<String, WorkerAction> BY_NAME =
      Map.of(
          "READ_CONTEXT", READ_CONTEXT,
          "WRITE_CONTEXT", WRITE_CONTEXT,
          "SIGNAL_CASE", SIGNAL_CASE,
          "READ_EVENT_LOG", READ_EVENT_LOG,
          "READ_PLAN_ITEMS", READ_PLAN_ITEMS,
          "SPAWN_SUB_CASE", SPAWN_SUB_CASE,
          "CLAIM_WORK_ITEM", CLAIM_WORK_ITEM,
          "ADMIN", ADMIN);

  private EngineWorkerActions() {}

  public static WorkerAction fromKebabCase(String kebab) {
    if (kebab == null) {
      throw new IllegalArgumentException("action name must not be null");
    }
    String key = kebab.toUpperCase().replace('-', '_');
    WorkerAction action = BY_NAME.get(key);
    if (action == null) {
      throw new IllegalArgumentException("Unknown engine worker action: " + kebab);
    }
    return action;
  }
}
