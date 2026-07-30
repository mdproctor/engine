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

import io.casehub.api.model.TaskStatus;
import java.time.Instant;
import java.util.UUID;

public record PlanItemSaveRequest(
    UUID caseId,
    String planItemId,
    String bindingName,
    TaskStatus status,
    Instant createdAt,
    TargetType targetType,
    String outputMappingExpression,
    String tenancyId,
    String description,
    String executorName,
    String executorDescription,
    PlanItemType planItemType,
    String planningStrategy,
    String completionSemantics,
    String dispatchMode,
    boolean repeatable,
    String parentCompoundId,
    String lifecycleScope) {

  public static PlanItemSaveRequest primitive(
      UUID caseId,
      String planItemId,
      String bindingName,
      TaskStatus status,
      Instant createdAt,
      TargetType targetType,
      String outputMappingExpression,
      String tenancyId,
      String description,
      String executorName,
      String executorDescription) {
    return new PlanItemSaveRequest(
        caseId,
        planItemId,
        bindingName,
        status,
        createdAt,
        targetType,
        outputMappingExpression,
        tenancyId,
        description,
        executorName,
        executorDescription,
        PlanItemType.PRIMITIVE,
        null,
        null,
        null,
        false,
        null,
        null);
  }
}
