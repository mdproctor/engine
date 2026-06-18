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

/**
 * Lifecycle states for a {@code CaseInstance}.
 *
 * <p>Aligned with {@code io.serverlessworkflow.impl.WorkflowStatus} as used by Quarkus Flow and the
 * CNCF Serverless Workflow specification.
 *
 * <p>{@code PENDING} is intentionally absent: the async event cycle transitions a case directly to
 * {@code RUNNING} on creation. PENDING semantics for plan items and stages are managed by the
 * {@code casehub-blackboard} module, not by {@code CaseInstance}.
 */
public enum CaseStatus {
  /** Case is initializing — cached but event handlers have not yet completed. */
  STARTING,
  /** Case is actively executing. */
  RUNNING,
  /** Case is blocked waiting for an external event or signal. */
  WAITING,
  /** Case has been paused by an administrative action. */
  SUSPENDED,
  /** Case completed successfully. */
  COMPLETED,
  /** Case terminated due to an error. */
  FAULTED,
  /** Case was stopped before completion. */
  CANCELLED
}
