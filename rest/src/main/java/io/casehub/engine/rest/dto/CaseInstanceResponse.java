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

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Case instance status and metadata")
public record CaseInstanceResponse(
    @Schema(description = "Case instance UUID", required = true) @NotNull UUID caseId,
    @Schema(description = "Current case status", required = true, example = "RUNNING") @NotNull
        CaseStatus status,
    @Schema(description = "Case namespace", required = true, example = "acme") @NotBlank
        String namespace,
    @Schema(description = "Case name", required = true, example = "Order Processing") @NotBlank
        String name,
    @Schema(description = "Case version", required = true, example = "1.0.0") @NotBlank
        String version,
    @Schema(description = "Case creation timestamp", required = true) @NotNull Instant createdAt) {

  public static CaseInstanceResponse from(CaseInstance instance) {
    CaseMetaModel meta = instance.getCaseMetaModel();
    return new CaseInstanceResponse(
        instance.getUuid(),
        instance.getState(),
        meta.getNamespace(),
        meta.getName(),
        meta.getVersion(),
        meta.getCreatedAt());
  }
}
