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

import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Case completion summary")
public record CompletionSummary(
    @Schema(description = "Completion type: goal-based or predicate-based") String type,
    @Schema(
            description = "Overall satisfied (predicate-based only, null for goal-based)",
            nullable = true)
        Boolean satisfied,
    @Schema(description = "Per-kind completion status (goal-based only, empty for predicate-based)")
        Map<String, CompletionStatus> byKind) {}
