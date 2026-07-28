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
package io.casehub.engine.planning.event;

/**
 * EventBus addresses for casehub-blackboard events. Published via {@code eventBus.publish()} —
 * fan-out, multiple consumers allowed. See casehubio/engine#76.
 */
public final class BlackboardEventBusAddresses {
  private BlackboardEventBusAddresses() {}

  public static final String STAGE_ACTIVATED = "casehub.blackboard.stage.activated";
  public static final String STAGE_COMPLETED = "casehub.blackboard.stage.completed";
  public static final String STAGE_TERMINATED = "casehub.blackboard.stage.terminated";

  /** Published by SubCaseCompletionService when a child case terminates and the parent resumes. */
  public static final String SUBCASE_EXECUTION_COMPLETED =
      "casehub.blackboard.subcase.execution.completed";

  public static final String COMPOUND_COMPLETED = "casehub.planning.compound.completed";
}
