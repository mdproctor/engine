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

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Paginated response wrapper")
public record PagedResponse<T>(
    @Schema(description = "Page content items") List<T> items,
    @Schema(description = "Current page number (1-indexed)", example = "1") int page,
    @Schema(description = "Page size", example = "20") int size,
    @Schema(description = "Total number of elements", example = "42") long totalElements,
    @Schema(description = "Total number of pages", example = "3") int totalPages) {}
