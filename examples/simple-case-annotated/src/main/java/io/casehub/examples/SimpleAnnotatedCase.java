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

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.annotations.Bind;
import io.casehub.engine.annotations.Case;
import io.casehub.engine.annotations.Customize;
import io.casehub.engine.annotations.Goal;
import io.casehub.engine.annotations.Milestone;
import io.casehub.engine.annotations.Worker;

@Case(
    namespace = "banking",
    name = "CustomerOnboarding",
    version = "1.0.0",
    title = "Customer Onboarding",
    summary =
        "Opens a new bank account — verifies identity, runs compliance checks, provisions the account")
public interface SimpleAnnotatedCase {

  @Worker(capability = "verifyIdentity", description = "Verifies customer identity documents")
  @Bind(contextChange = ".application != null")
  default IdentityResult verifyIdentity(String application) {
    return new IdentityResult(true, "ID-" + application.hashCode());
  }

  @Worker(capability = "complianceCheck", description = "Runs KYC/AML compliance screening")
  @Bind(contextChange = ".identityResult != null", when = ".identityResult.verified == true")
  default ComplianceResult checkCompliance(IdentityResult identityResult) {
    return new ComplianceResult("PASS", identityResult.referenceId());
  }

  @Worker(capability = "provisionAccount")
  @Bind(contextChange = ".complianceResult != null", when = ".complianceResult.status == 'PASS'")
  default Account provisionAccount(ComplianceResult complianceResult) {
    return new Account("ACC-" + complianceResult.referenceId(), "ACTIVE");
  }

  @Milestone(
      name = "identityVerified",
      completionCriteria = ".identityResult.verified == true",
      entryCriteria = ".application != null")
  default void identityVerified() {}

  @Goal(value = "Account opened successfully", condition = ".account != null")
  default void accountOpened() {}

  @Customize
  static void customize(CaseDefinition.Builder builder) {
    builder.title("Customer Onboarding — New Account");
  }

  record IdentityResult(boolean verified, String referenceId) {}

  record ComplianceResult(String status, String referenceId) {}

  record Account(String accountId, String status) {}
}
