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
package io.casehub.engine.scheduler.quartz;

import java.util.UUID;
import org.quartz.JobExecutionContext;

/**
 * Value object carrying the data needed by {@link QuartzRetryService} for retry scheduling and
 * failure counting. Built from the Quartz {@link JobExecutionContext} job data map.
 */
record WorkerRetryContext(
    UUID caseId,
    String workerId,
    String inputDataHash,
    String tenancyId,
    String eventLogId,
    String bindingName,
    UUID signalId) {

  static WorkerRetryContext from(final JobExecutionContext context) {
    String signalIdStr = context.getMergedJobDataMap().getString("signalId");
    return new WorkerRetryContext(
        UUID.fromString(context.getMergedJobDataMap().getString("caseHubInstanceUuid")),
        context.getMergedJobDataMap().getString("workerId"),
        context.getMergedJobDataMap().getString("inputDataHash"),
        context.getMergedJobDataMap().getString("tenancyId"),
        context.getMergedJobDataMap().getString("eventLogId"),
        context.getMergedJobDataMap().getString("bindingName"),
        signalIdStr != null ? UUID.fromString(signalIdStr) : null);
  }

  WorkerRetryContext withBindingName(String bindingName) {
    return new WorkerRetryContext(
        caseId, workerId, inputDataHash, tenancyId, eventLogId, bindingName, signalId);
  }

  WorkerRetryContext withSignalId(UUID signalId) {
    return new WorkerRetryContext(
        caseId, workerId, inputDataHash, tenancyId, eventLogId, bindingName, signalId);
  }
}
