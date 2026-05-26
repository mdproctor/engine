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
package io.casehub.engine.common.internal.model;

/**
 * Lifecycle states for a {@link io.casehub.blackboard.plan.PlanItem}.
 *
 * <p>RUNNING: a Quartz job is actively executing this binding (CapabilityTarget only). DELEGATED:
 * control has passed to an external actor (SubCase, HumanTask, Extension) and the engine is waiting
 * for a completion signal. These two active states are semantically distinct — consumers and LLMs
 * must not conflate "local computation running" with "waiting for external actor".
 *
 * <p>REJECTED: an external actor explicitly refused the work (human task refusal or M-of-N group
 * threshold failure). Distinct from FAULTED (computation or timeout failure). Stored as STRING in
 * JPA — ordinal safety is not a concern.
 */
public enum PlanItemStatus {
  PENDING,
  RUNNING,
  DELEGATED,
  COMPLETED,
  FAULTED,
  REJECTED,
  CANCELLED
}
