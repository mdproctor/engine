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
package io.casehub.engine.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.CaseMetaModelRepository;
import io.casehub.engine.plan.JoinType;
import io.casehub.engine.plan.execution.DagResultSnapshot;
import io.casehub.engine.plan.execution.ExecutionSnapshotStore;
import io.casehub.engine.plan.execution.NodeStateSnapshot;
import io.casehub.engine.plan.snapshot.DagNodeSnapshot;
import io.casehub.engine.plan.snapshot.DagPlanSnapshot;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PlanResourceTest {

  @Inject ExecutionSnapshotStore snapshotStore;
  @Inject CaseInstanceRepository instanceRepository;
  @Inject CaseMetaModelRepository metaModelRepository;
  @Inject TestCaseDefinitionRegistry definitionRegistry;

  @Test
  void getDagPlanReturns404ForUnknownCase() {
    given().when().get("/api/v1/cases/" + UUID.randomUUID() + "/plan/dag").then().statusCode(404);
  }

  @Test
  void getDagResultReturns404ForUnknownCase() {
    given()
        .when()
        .get("/api/v1/cases/" + UUID.randomUUID() + "/plan/dag/result")
        .then()
        .statusCode(404);
  }

  @Test
  void getDecompositionReturns404ForUnknownCase() {
    given()
        .when()
        .get("/api/v1/cases/" + UUID.randomUUID() + "/plan/decomposition")
        .then()
        .statusCode(404);
  }

  @Test
  void getPlanModelReturns404WhenNoProvider() {
    given().when().get("/api/v1/cases/" + UUID.randomUUID() + "/plan/model").then().statusCode(404);
  }

  @Test
  void getDefinitionsReturns404ForUnknownCase() {
    given()
        .when()
        .get("/api/v1/cases/" + UUID.randomUUID() + "/plan/definitions")
        .then()
        .statusCode(404);
  }

  @Test
  void getStateReturns404WhenNoSnapshotsAvailable() {
    given().when().get("/api/v1/cases/" + UUID.randomUUID() + "/plan/state").then().statusCode(404);
  }

  @Test
  void getStateReturns200WithCompletedExecution() {
    UUID caseId = createCase("state-test-complete");

    snapshotStore.storeDagPlan(
        caseId,
        new DagPlanSnapshot(
            Map.of(
                "n1",
                new DagNodeSnapshot(
                    "n1", "t1", "Analyse data", "analyst", Set.of(), JoinType.ALL_OF)),
            Instant.parse("2026-08-17T09:00:00Z")));

    snapshotStore.storeDagResult(
        caseId,
        new DagResultSnapshot(
            Map.of("n1", new NodeStateSnapshot("Completed", null)),
            Map.of(),
            true,
            Duration.ofSeconds(10),
            Instant.parse("2026-08-17T09:00:10Z")));

    given()
        .when()
        .get("/api/v1/cases/" + caseId + "/plan/state")
        .then()
        .statusCode(200)
        .body("executionId", equalTo(caseId.toString()))
        .body("state", equalTo("COMPLETE"))
        .body("result", equalTo("COMPLETED"))
        .body("model", notNullValue())
        .body("model.pattern", equalTo("SEQUENCE"))
        .body("model.failurePolicy.routingFailureAction", equalTo("RETRY_BROADER"))
        .body("activeAgents", hasSize(0))
        .body("completedAgents", hasSize(1))
        .body("completedAgents[0].agentRef.name", equalTo("analyst"))
        .body("completedAgents[0].status", equalTo("SUCCESS"))
        .body("startedAt", notNullValue())
        .body("completedAt", notNullValue());
  }

  @Test
  void getStateReturnsFaultedForFailedExecution() {
    UUID caseId = createCase("state-test-faulted");

    snapshotStore.storeDagResult(
        caseId,
        new DagResultSnapshot(
            Map.of("n1", new NodeStateSnapshot("Failed", "timeout")),
            Map.of(),
            false,
            Duration.ofSeconds(60),
            Instant.parse("2026-08-17T10:01:00Z")));

    given()
        .when()
        .get("/api/v1/cases/" + caseId + "/plan/state")
        .then()
        .statusCode(200)
        .body("state", equalTo("FAULTED"))
        .body("result", equalTo("FAILED"));
  }

  @Test
  void getStateDetectsParallelPattern() {
    UUID caseId = createCase("state-test-parallel");

    snapshotStore.storeDagPlan(
        caseId,
        new DagPlanSnapshot(
            Map.of(
                "n1",
                new DagNodeSnapshot("n1", "t1", "Task A", "w1", Set.of(), JoinType.ALL_OF),
                "n2",
                new DagNodeSnapshot("n2", "t2", "Task B", "w2", Set.of(), JoinType.ALL_OF)),
            Instant.parse("2026-08-17T09:00:00Z")));

    given()
        .when()
        .get("/api/v1/cases/" + caseId + "/plan/state")
        .then()
        .statusCode(200)
        .body("model.pattern", equalTo("PARALLEL"));
  }

  @Test
  void getStateReturnsIdleWhenNoDagResultAndNoPlanModel() {
    UUID caseId = createCase("state-test-idle");

    snapshotStore.storeDagPlan(
        caseId,
        new DagPlanSnapshot(
            Map.of(
                "n1", new DagNodeSnapshot("n1", "t1", "Task A", "w1", Set.of(), JoinType.ALL_OF)),
            Instant.parse("2026-08-17T09:00:00Z")));

    given()
        .when()
        .get("/api/v1/cases/" + caseId + "/plan/state")
        .then()
        .statusCode(200)
        .body("state", equalTo("IDLE"))
        .body("result", nullValue());
  }

  private UUID createCase(String name) {
    UUID caseId = UUID.randomUUID();

    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace("test");
    meta.setName(name);
    meta.setVersion("1.0.0");
    meta = metaModelRepository.save(meta, "test-tenant");

    CaseDefinition def =
        CaseDefinition.builder().namespace("test").name(name).version("1.0.0").build();
    definitionRegistry.register(def, meta);

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setCaseMetaModel(meta);
    instance.setState(CaseStatus.RUNNING);
    instanceRepository.save(instance, "test-tenant");

    return caseId;
  }
}
