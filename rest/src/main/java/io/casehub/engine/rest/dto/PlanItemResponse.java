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

import io.casehub.engine.common.internal.model.PlanItemRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Plan item status from case execution")
public record PlanItemResponse(
    @Schema(description = "Plan item UUID", required = true) @NotBlank String planItemId,
    @Schema(description = "Binding that created this plan item", required = true) @NotBlank
        String bindingName,
    @Schema(
            description = "Target type: capability, human-task, sub-case, extension",
            required = true)
        @NotBlank
        String targetType,
    @Schema(
            description =
                "Execution status: PENDING, RUNNING, DELEGATED, SUSPENDED, COMPLETED, FAULTED, REJECTED, OBSOLETE, CANCELLED",
            required = true)
        @NotBlank
        String status,
    @Schema(description = "Assigned executor", nullable = true) String executorName,
    @Schema(description = "Binding description", nullable = true) String description,
    @Schema(description = "Creation timestamp", required = true) @NotNull Instant createdAt) {

  public static PlanItemResponse from(PlanItemRecord record) {
    return new PlanItemResponse(
        record.planItemId(),
        record.bindingName(),
        record.targetType().name().toLowerCase().replace('_', '-'),
        record.status().name(),
        record.executorName(),
        record.description(),
        record.createdAt());
  }
}
