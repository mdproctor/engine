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
package io.casehub.engine;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.Milestone;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.persistence.memory.InMemoryCaseInstanceRepository;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Reproduces the AML tenant mismatch regression after engine#680 removed the
 * TestCaseInstanceRepository findByUuid workaround.
 *
 * <p>CDI config mirrors consumer projects: CaseInstance repos activate via {@code @Priority(1)}
 * subclasses (NOT via {@code selected-alternatives}). Other repos use {@code
 * selected-alternatives}. This is the pattern used by AML, clinical, and other consumer modules via
 * engine-testing.
 */
@QuarkusTest
@TestProfile(ConsumerInMemoryStartCaseTest.ConsumerProfile.class)
public class ConsumerInMemoryStartCaseTest {

  @Inject ConsumerCaseHub caseHub;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject CaseInstanceRepository caseInstanceRepo;

  @Test
  void startCase_throughEventBus_shouldNotThrowTenantMismatch() {
    UUID caseId = caseHub.startCase(Map.of("documentId", "doc-001", "status", "processing"));

    assertNotNull(caseId, "caseId should be assigned");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertNotNull(instance, "instance should be in cache");
              assertEquals(
                  CaseStatus.COMPLETED,
                  instance.getState(),
                  "case should reach COMPLETED (was: " + instance.getState() + ")");
            });
  }

  @Test
  void directRepoOps_saveAndUpdate_shouldWork() {
    var instance = new io.casehub.engine.common.internal.model.CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(CaseStatus.STARTING);

    String tenancyId = "test-tenant";
    caseInstanceRepo.save(instance, tenancyId);

    var found = caseInstanceRepo.findByUuid(instance.getUuid(), tenancyId);
    assertNotNull(found, "findByUuid should return the saved instance");
    assertEquals(tenancyId, found.tenancyId);

    instance.setState(CaseStatus.RUNNING);
    caseInstanceRepo.update(instance, tenancyId);

    var updated = caseInstanceRepo.findByUuid(instance.getUuid(), tenancyId);
    assertEquals(CaseStatus.RUNNING, updated.getState());
  }

  @Test
  void repoDelegate_shouldShareSameStore() {
    assertNotNull(caseInstanceRepo, "repo should be injected");

    var instance = new io.casehub.engine.common.internal.model.CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(CaseStatus.STARTING);

    String tenancyId = "delegate-test-tenant";

    caseInstanceRepo.save(instance, tenancyId);

    var found = caseInstanceRepo.findByUuid(instance.getUuid(), tenancyId);
    assertNotNull(found, "repo should find instance saved (same store)");
  }

  @Test
  void startCase_shouldSetActorIdOnInstance() {
    UUID caseId = caseHub.startCase(Map.of("documentId", "doc-002", "status", "new"));
    assertNotNull(caseId, "caseId should be assigned");

    var instance = caseInstanceCache.get(caseId);
    assertNotNull(instance, "instance should be in cache");
    assertEquals(
        "system", instance.getActorId(), "actorId should match CurrentPrincipal.actorId()");
  }

  // ── CaseHub definition ──

  @ApplicationScoped
  public static class ConsumerCaseHub extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      Capability capability =
          Capability.builder()
              .name("processDocument")
              .inputSchema("{ documentId: .documentId, status: .status }")
              .outputSchema("{ processedDocument: ., status: .status }")
              .description("Process a document")
              .build();

      Goal goal =
          Goal.builder()
              .name("done")
              .condition(".status == \"processed\"")
              .kind(GoalKind.SUCCESS)
              .build();

      return CaseDefinition.builder()
          .namespace("consumer-test")
          .name("Consumer InMemory Test")
          .version("1.0.0")
          .title("Reproduces AML tenant mismatch")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("processor")
                  .capabilityName("processDocument")
                  .function(
                      input ->
                          WorkerResult.of(
                              Map.of(
                                  "processedDocument",
                                  Map.of("id", input.get("documentId")),
                                  "status",
                                  "processed")))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger-processing")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".status == \"processing\""))
                  .build())
          .milestones(
              Milestone.builder()
                  .name("processed")
                  .completionCriteria(".status == \"processed\"")
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }

  // ── Alternative repo subclasses — mimics engine-testing's Test* repos ──
  // @Alternative WITHOUT @Priority — only enabled via getEnabledAlternatives() below.
  // @Priority(1) would make these globally enabled across ALL test profiles,
  // contaminating other profiles' CDI contexts and causing intermittent boot failures.

  @Alternative
  @ApplicationScoped
  public static class ProfileScopedCaseInstanceRepository extends InMemoryCaseInstanceRepository {}

  // ── Test profile ──

  public static class ConsumerProfile implements QuarkusTestProfile {
    @Override
    public String getConfigProfile() {
      return "consumer-inmemory";
    }
  }
}
