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
import io.casehub.api.model.ScheduleTrigger;
import io.casehub.api.model.StandardGoalKind;
import io.casehub.platform.api.path.Path;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;

/**
 * Choreography — Customer Onboarding (DSL pathway).
 *
 * <p>Same case as {@code examples/yaml/choreography-onboarding.yaml} and {@code
 * examples/choreography-annotated/}. Each binding fires independently when its context conditions
 * are met — no central sequencer.
 *
 * <p>See also: examples/yaml/choreography-onboarding.yaml (YAML pathway)
 * examples/choreography-annotated/ (annotation pathway)
 */
public final class ChoreographyOnboardingCase {

  private ChoreographyOnboardingCase() {}

  public static CaseDefinition define() {
    Capability verifyIdentity =
        Capability.of(
            "verifyIdentity",
            "{ application: .application }",
            "{ identityResult: { verified: .verified, referenceId: .referenceId } }");

    Capability kycScreening =
        Capability.of(
            "kycScreening",
            "{ identityResult: .identityResult }",
            "{ complianceResult: { status: .status, referenceId: .referenceId } }");

    Capability provisionAccount =
        Capability.of(
            "provisionAccount",
            "{ complianceResult: .complianceResult }",
            "{ account: { accountId: .accountId, status: .status } }");

    return CaseDefinition.builder()
        .namespace("banking")
        .name("customer-onboarding")
        .version("1.0.0")
        .title("Customer Onboarding")
        .summary(
            "Opens a new bank account — verifies identity, runs compliance checks, provisions the account")
        .type(Path.parse("banking/onboarding"))
        .label(Path.parse("example/choreography"))
        .capabilities(verifyIdentity, kycScreening, provisionAccount)
        .workers(
            Worker.builder()
                .name("identity-verifier")
                .capabilityName("verifyIdentity")
                .noFunction()
                .build(),
            Worker.builder()
                .name("kyc-screener")
                .capabilityName("kycScreening")
                .noFunction()
                .build(),
            Worker.builder()
                .name("account-provisioner")
                .capabilityName("provisionAccount")
                .noFunction()
                .build())
        .bindings(
            Binding.builder()
                .name("verify-on-application")
                .capability(verifyIdentity)
                .on(new ContextChangeTrigger(".application != null and .identityResult == null"))
                .build(),
            Binding.builder()
                .name("screen-after-verified")
                .capability(kycScreening)
                .on(
                    new ContextChangeTrigger(
                        ".identityResult != null and .complianceResult == null"))
                .when(".identityResult.verified == true")
                .build(),
            Binding.builder()
                .name("provision-after-compliant")
                .capability(provisionAccount)
                .on(new ContextChangeTrigger(".complianceResult != null and .account == null"))
                .when(".complianceResult.status == \"PASS\"")
                .build(),
            Binding.builder()
                .name("periodic-compliance-recheck")
                .capability(kycScreening)
                .on(ScheduleTrigger.cron("0 0 * * * ?"))
                .build())
        .milestones(
            Milestone.builder()
                .name("identityVerified")
                .entryCriteria(".application != null")
                .completionCriteria(".identityResult != null and .identityResult.verified == true")
                .build(),
            Milestone.builder()
                .name("complianceCleared")
                .completionCriteria(
                    ".complianceResult != null and .complianceResult.status == \"PASS\"")
                .build())
        .goals(
            Goal.builder()
                .name("accountOpened")
                .kind(StandardGoalKind.SUCCESS)
                .condition(".account != null and .account.status == \"ACTIVE\"")
                .build(),
            Goal.builder()
                .name("complianceFailed")
                .kind(StandardGoalKind.FAILURE)
                .condition(".complianceResult != null and .complianceResult.status == \"FAIL\"")
                .build())
        .completion(GoalExpression.goal("accountOpened"), GoalExpression.goal("complianceFailed"))
        .build();
  }
}
