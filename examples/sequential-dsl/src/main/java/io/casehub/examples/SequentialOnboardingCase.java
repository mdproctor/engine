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
package io.casehub.examples;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.StandardGoalKind;
import io.casehub.platform.api.path.Path;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;

public final class SequentialOnboardingCase {

  private SequentialOnboardingCase() {}

  public static CaseDefinition define() {
    Capability collectDocuments =
        Capability.of(
            "collectDocuments",
            "{ employeeId: .employee.id, requiredDocs: .employee.requiredDocuments }",
            "{ documentsCollected: { complete: .complete, missing: .missing } }");

    Capability backgroundCheck =
        Capability.of(
            "backgroundCheck",
            "{ employeeId: .employee.id, documents: .documentsCollected }",
            "{ backgroundResult: { status: .status, completedAt: .completedAt } }");

    Capability provisionAccess =
        Capability.of(
            "provisionAccess",
            "{ employeeId: .employee.id, department: .employee.department, role: .employee.role }",
            "{ accessProvisioned: { email: .email, systems: .systems } }");

    Capability scheduleOrientation =
        Capability.of(
            "scheduleOrientation",
            "{ employeeId: .employee.id, startDate: .employee.startDate,"
                + " department: .employee.department }",
            "{ orientation: { date: .date, location: .location, confirmed: .confirmed } }");

    return CaseDefinition.builder()
        .namespace("hr")
        .name("employee-onboarding")
        .version("1.0.0")
        .title("Employee Onboarding")
        .summary(
            "Onboards a new employee — collects documents, runs background check,"
                + " provisions access, schedules orientation")
        .type(Path.parse("hr/onboarding"))
        .label(Path.parse("example/sequential"))
        .planningStrategy("sequential")
        .capabilities(collectDocuments, backgroundCheck, provisionAccess, scheduleOrientation)
        .workers(
            Worker.builder()
                .name("doc-collector")
                .capabilityName("collectDocuments")
                .noFunction()
                .build(),
            Worker.builder()
                .name("bg-checker")
                .capabilityName("backgroundCheck")
                .noFunction()
                .build(),
            Worker.builder()
                .name("access-provisioner")
                .capabilityName("provisionAccess")
                .noFunction()
                .build(),
            Worker.builder()
                .name("orientation-scheduler")
                .capabilityName("scheduleOrientation")
                .noFunction()
                .build())
        .bindings(
            Binding.builder()
                .name("collect-docs")
                .capability(collectDocuments)
                .on(new ContextChangeTrigger(".employee != null and .documentsCollected == null"))
                .build(),
            Binding.builder()
                .name("run-background-check")
                .capability(backgroundCheck)
                .on(
                    new ContextChangeTrigger(
                        ".documentsCollected != null and .backgroundResult == null"))
                .build(),
            Binding.builder()
                .name("provision-it-access")
                .capability(provisionAccess)
                .on(
                    new ContextChangeTrigger(
                        ".backgroundResult != null and .accessProvisioned == null"))
                .build(),
            Binding.builder()
                .name("schedule-orientation")
                .capability(scheduleOrientation)
                .on(new ContextChangeTrigger(".accessProvisioned != null and .orientation == null"))
                .build())
        .milestones(
            Milestone.builder()
                .name("documentsComplete")
                .completionCriteria(
                    ".documentsCollected != null and .documentsCollected.complete == true")
                .build(),
            Milestone.builder()
                .name("backgroundCleared")
                .completionCriteria(
                    ".backgroundResult != null and .backgroundResult.status == \"CLEAR\"")
                .build(),
            Milestone.builder()
                .name("accessReady")
                .completionCriteria(".accessProvisioned != null")
                .build())
        .goals(
            Goal.builder()
                .name("onboardingComplete")
                .kind(StandardGoalKind.SUCCESS)
                .condition(".orientation != null and .orientation.confirmed == true")
                .build(),
            Goal.builder()
                .name("backgroundFailed")
                .kind(StandardGoalKind.FAILURE)
                .condition(".backgroundResult != null and .backgroundResult.status == \"FAIL\"")
                .build())
        .completion(
            GoalExpression.goal("onboardingComplete"), GoalExpression.goal("backgroundFailed"))
        .build();
  }
}
