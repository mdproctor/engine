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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseDefinition;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class SimpleAnnotatedCaseTest {

  @RegisterExtension
  static final QuarkusUnitTest test =
      new QuarkusUnitTest()
          .withApplicationRoot(
              root ->
                  root.addClasses(
                      SimpleAnnotatedCase.class,
                      SimpleAnnotatedCase.IdentityResult.class,
                      SimpleAnnotatedCase.ComplianceResult.class,
                      SimpleAnnotatedCase.Account.class));

  @Inject CaseDefinition definition;

  @Test
  void namespace_and_name() {
    assertThat(definition.getNamespace()).isEqualTo("banking");
    assertThat(definition.getName()).isEqualTo("CustomerOnboarding");
    assertThat(definition.getVersion()).isEqualTo("1.0.0");
  }

  @Test
  void title_from_annotation_and_customize() {
    assertThat(definition.getTitle()).isEqualTo("Customer Onboarding — New Account");
  }

  @Test
  void summary_from_annotation() {
    assertThat(definition.getSummary()).contains("bank account");
  }

  @Test
  void three_workers_with_descriptions() {
    assertThat(definition.getWorkers()).hasSize(3);
    assertThat(definition.getWorkers().stream().map(w -> w.name()).toList())
        .containsExactlyInAnyOrder("verifyIdentity", "checkCompliance", "provisionAccount");
    assertThat(
            definition.getWorkers().stream()
                .filter(w -> w.name().equals("verifyIdentity"))
                .findFirst()
                .get()
                .description())
        .contains("identity documents");
  }

  @Test
  void bindings_with_when_guards() {
    var complianceBinding =
        definition.getBindings().stream()
            .filter(b -> b.getName().equals("checkCompliance"))
            .findFirst();
    assertThat(complianceBinding).isPresent();
    assertThat(complianceBinding.get().getWhen()).isNotNull();
  }

  @Test
  void goal_for_account_opening() {
    assertThat(definition.getGoals()).hasSize(1);
    assertThat(definition.getGoals().get(0).getName()).isEqualTo("accountOpened");
    assertThat(definition.getGoals().get(0).getDescription()).contains("Account opened");
  }

  @Test
  void milestone_for_identity_verification() {
    assertThat(definition.getMilestones()).hasSize(1);
    assertThat(definition.getMilestones().get(0).getName()).isEqualTo("identityVerified");
  }

  @Test
  void three_capabilities() {
    assertThat(definition.getCapabilities()).hasSize(3);
    assertThat(definition.getCapabilities().stream().map(c -> c.name()).toList())
        .containsExactlyInAnyOrder("verifyIdentity", "complianceCheck", "provisionAccount");
  }
}
