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
package io.casehub.blackboard.event;

import java.util.UUID;

/**
 * Published on the Vert.x event bus at {@link
 * BlackboardEventBusAddresses#SUBCASE_EXECUTION_COMPLETED} by {@link
 * io.casehub.blackboard.subcase.SubCaseCompletionService} after a child case terminates and the
 * parent case has been resumed (or was not waiting). Consumed by {@link
 * io.casehub.blackboard.handler.PlanItemCompletionHandler} to mark the SubCase PlanItem COMPLETED
 * and trigger stage autocomplete.
 *
 * @param parentCaseId the parent case whose SubCase binding produced the child
 * @param childCaseId the child case that just terminated
 * @param tenancyId the tenant that owns the parent case
 */
public record SubCaseExecutionCompleted(UUID parentCaseId, UUID childCaseId, String tenancyId) {}
