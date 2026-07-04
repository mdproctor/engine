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
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DefaultCaseDefinitionRegistryTest {

  @Inject DefaultCaseDefinitionRegistry registry;

  @Test
  void getCaseDefinition_afterKeyFieldMutation_stillFindsEntry() {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test-ns-reg")
            .name("test-case-reg")
            .version("1.0")
            .build();

    CaseMetaModel registered =
        registry
            .registerCaseDefinition(def)
            .subscribe()
            .asCompletionStage()
            .toCompletableFuture()
            .join();

    assertThat(registered).isNotNull();

    // Immediately after registration — must find it
    CaseDefinition found = registry.getCaseDefinition(registered);
    assertThat(found).isNotNull();
    assertThat(found.getName()).isEqualTo("test-case-reg");

    // Mutate the registered CaseMetaModel's key fields
    registered.setNamespace("mutated-namespace");
    registered.setName("mutated-name");

    // Fresh CaseMetaModel with original coordinates — must still find the entry
    CaseMetaModel lookup = new CaseMetaModel();
    lookup.setNamespace("test-ns-reg");
    lookup.setName("test-case-reg");
    lookup.setVersion("1.0");

    CaseDefinition foundAfterMutation = registry.getCaseDefinition(lookup);
    assertThat(foundAfterMutation)
        .as("getCaseDefinition must find the entry even after registered key was mutated")
        .isNotNull();
  }

  @Test
  void registerCaseDefinition_earlyExitPath_returnsExistingCaseMetaModel() {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test-ns-reg2")
            .name("test-case-reg2")
            .version("1.0")
            .build();

    CaseMetaModel first =
        registry
            .registerCaseDefinition(def)
            .subscribe()
            .asCompletionStage()
            .toCompletableFuture()
            .join();

    CaseMetaModel second =
        registry
            .registerCaseDefinition(def)
            .subscribe()
            .asCompletionStage()
            .toCompletableFuture()
            .join();

    assertThat(second).isNotNull();
    assertThat(second.getNamespace()).isEqualTo("test-ns-reg2");
    assertThat(second.getName()).isEqualTo(first.getName());
  }

  @Test
  void registerCaseDefinition_duplicateKeyFromDifferentObject_returnsExistingMetaModel() {
    CaseDefinition first =
        CaseDefinition.builder()
            .namespace("test-collision")
            .name("collision-case")
            .version("1.0")
            .build();

    CaseDefinition second =
        CaseDefinition.builder()
            .namespace("test-collision")
            .name("collision-case")
            .version("1.0")
            .build();

    CaseMetaModel firstResult =
        registry
            .registerCaseDefinition(first)
            .subscribe()
            .asCompletionStage()
            .toCompletableFuture()
            .join();

    CaseMetaModel secondResult =
        registry
            .registerCaseDefinition(second)
            .subscribe()
            .asCompletionStage()
            .toCompletableFuture()
            .join();

    assertThat(secondResult).isNotNull();
    assertThat(secondResult.getName()).isEqualTo(firstResult.getName());

    // The registered definition should be the FIRST one — "first wins"
    CaseDefinition resolved = registry.getCaseDefinition(firstResult);
    assertThat(resolved).isSameAs(first);
  }

  @Test
  void findByIdentity_registeredDefinition_returnsMetaModel() {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test-find-id")
            .name("findable-case")
            .version("1.0")
            .build();

    CaseMetaModel registered =
        registry
            .registerCaseDefinition(def)
            .subscribe()
            .asCompletionStage()
            .toCompletableFuture()
            .join();

    Optional<CaseMetaModel> found = registry.findByIdentity("test-find-id", "findable-case", "1.0");

    assertThat(found).isPresent();
    assertThat(found.get().getNamespace()).isEqualTo("test-find-id");
    assertThat(found.get().getName()).isEqualTo("findable-case");
    assertThat(found.get().getVersion()).isEqualTo("1.0");
  }

  @Test
  void findByIdentity_unknownDefinition_returnsEmpty() {
    Optional<CaseMetaModel> found = registry.findByIdentity("no-such-ns", "no-such-case", "99.0");

    assertThat(found).isEmpty();
  }

  @Test
  void findByName_existingDefinition_returnsDefinition() {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test-findbyname")
            .name("findable-by-name")
            .version("1.0")
            .build();

    registry
        .registerCaseDefinition(def)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();

    Optional<CaseDefinition> found = registry.findByName("findable-by-name");

    assertThat(found).isPresent();
    assertThat(found.get().getName()).isEqualTo("findable-by-name");
    assertThat(found.get().getNamespace()).isEqualTo("test-findbyname");
  }

  @Test
  void findByName_nonExistent_returnsEmpty() {
    Optional<CaseDefinition> found = registry.findByName("no-such-definition-xyz");

    assertThat(found).isEmpty();
  }

  @Test
  void findByName_ambiguous_throws() {
    CaseDefinition def1 =
        CaseDefinition.builder()
            .namespace("ns-ambig-a")
            .name("ambiguous-case")
            .version("1.0")
            .build();
    CaseDefinition def2 =
        CaseDefinition.builder()
            .namespace("ns-ambig-b")
            .name("ambiguous-case")
            .version("1.0")
            .build();

    registry
        .registerCaseDefinition(def1)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();
    registry
        .registerCaseDefinition(def2)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();

    assertThatThrownBy(() -> registry.findByName("ambiguous-case"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Ambiguous caseType")
        .hasMessageContaining("ambiguous-case");
  }
}
