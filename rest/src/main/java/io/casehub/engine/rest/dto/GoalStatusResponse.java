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
package io.casehub.engine.rest.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Goal evaluation result against live case context")
public record GoalStatusResponse(
    @Schema(description = "Goal name", required = true) String name,
    @Schema(description = "Goal kind (SUCCESS, FAILURE, or custom)", required = true) String kind,
    @Schema(description = "Whether the goal condition is currently satisfied") boolean satisfied,
    @Schema(description = "Expression string (JQ), null if lambda-based", nullable = true)
        String condition,
    @Schema(description = "Error message if evaluation failed, null if clean", nullable = true)
        String error) {}
