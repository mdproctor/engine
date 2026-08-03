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

import io.casehub.api.model.event.ExecutionOrigin;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import java.util.List;
import java.util.UUID;

public record WorkerScheduleEvent(
    CaseInstance caseInstance,
    Worker worker,
    Capability capability,
    String bindingName,
    String inputProjectionOverride,
    UUID signalId,
    ExecutionOrigin origin,
    List<RetrievedExperience> experiences,
    io.casehub.api.model.LifecycleScope lifecycleScope,
    io.casehub.api.model.ExecutionMode executionMode,
    String workerCredentialToken,
    com.fasterxml.jackson.databind.JsonNode activationContext) {

  public WorkerScheduleEvent {
    experiences = experiences == null ? List.of() : experiences;
  }

  public WorkerScheduleEvent(
      CaseInstance caseInstance,
      Worker worker,
      Capability capability,
      String bindingName,
      String inputProjectionOverride,
      UUID signalId,
      ExecutionOrigin origin,
      List<RetrievedExperience> experiences) {
    this(
        caseInstance,
        worker,
        capability,
        bindingName,
        inputProjectionOverride,
        signalId,
        origin,
        experiences,
        null,
        null,
        null,
        null);
  }

  public WorkerScheduleEvent(
      CaseInstance caseInstance,
      Worker worker,
      Capability capability,
      String bindingName,
      String inputProjectionOverride) {
    this(
        caseInstance,
        worker,
        capability,
        bindingName,
        inputProjectionOverride,
        null,
        null,
        List.of(),
        null,
        null,
        null,
        null);
  }

  public WorkerScheduleEvent(
      CaseInstance caseInstance, Worker worker, Capability capability, String bindingName) {
    this(
        caseInstance,
        worker,
        capability,
        bindingName,
        null,
        null,
        null,
        List.of(),
        null,
        null,
        null,
        null);
  }

  public WorkerScheduleEvent(CaseInstance caseInstance, Worker worker, Capability capability) {
    this(
        caseInstance,
        worker,
        capability,
        null,
        null,
        null,
        null,
        List.of(),
        null,
        null,
        null,
        null);
  }

  public String effectiveInputProjection() {
    return inputProjectionOverride != null ? inputProjectionOverride : capability.inputSchema();
  }
}
