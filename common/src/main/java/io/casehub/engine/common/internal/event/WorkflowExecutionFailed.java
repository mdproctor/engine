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
package io.casehub.engine.common.internal.event;

import io.casehub.api.model.Capability;
import io.casehub.api.model.Worker;
import io.casehub.engine.common.internal.model.CaseInstance;

/**
 * Published on {@link EventBusAddresses#WORKFLOW_EXECUTION_FAILED} when the non-blocking workflow
 * future completes exceptionally. Carries enough context for {@code
 * QuartzWorkerExecutionJobListener} to persist {@code WORKER_EXECUTION_FAILED}, count retries, and
 * either reschedule or publish {@code WORKER_RETRIES_EXHAUSTED}.
 */
public record WorkflowExecutionFailed(
    CaseInstance caseInstance,
    Worker worker,
    Capability capability,
    String inputDataHash,
    String eventLogId,
    Throwable cause) {}
