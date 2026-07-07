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
package io.casehub.api.model.event;

/**
 * Provenance metadata for worker executions. Tags EventLog entries with the origination path that
 * triggered the worker execution.
 */
public enum ExecutionOrigin {
  /** Worker execution triggered by a capability binding dispatch (standard case-driven path). */
  BINDING_DISPATCH,

  /** Worker execution triggered by an explicit signal to the case. */
  SIGNAL,

  /** Worker execution triggered by a scheduled timer or cron trigger. */
  SCHEDULE_TRIGGER,

  /** Worker execution triggered by sub-case completion. */
  SUBCASE_COMPLETION,

  /** Worker execution triggered by recovery coordinator (restart/resume operations). */
  RECOVERY
}
