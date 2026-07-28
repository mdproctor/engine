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

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.CaseMetaModelRepository;
import io.casehub.engine.common.spi.PlanItemStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CaseInstancePlanItemsResourceTest {

  @Inject CaseInstanceRepository instanceRepository;
  @Inject CaseMetaModelRepository metaModelRepository;
  @Inject TestCaseDefinitionRegistry definitionRegistry;
  @Inject PlanItemStore planItemStore;

  private UUID caseId;

  @BeforeEach
  void setUp() {
    caseId = UUID.randomUUID();

    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace("test");
    meta.setName("plan-item-test");
    meta.setVersion("1.0.0");
    meta = metaModelRepository.save(meta, "test-tenant");

    CaseDefinition def =
        CaseDefinition.builder().namespace("test").name("plan-item-test").version("1.0.0").build();
    definitionRegistry.register(def, meta);

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setCaseMetaModel(meta);
    instance.setState(CaseStatus.RUNNING);
    instanceRepository.save(instance, "test-tenant");

    planItemStore.save(
        PlanItemSaveRequest.primitive(
            caseId,
            "pi-1",
            "code-analysis",
            TaskStatus.COMPLETED,
            Instant.parse("2026-07-21T10:00:00Z"),
            TargetType.CAPABILITY,
            null,
            "test-tenant",
            "Initial code analysis",
            null,
            null),
        "test-tenant");

    planItemStore.save(
        PlanItemSaveRequest.primitive(
            caseId,
            "pi-2",
            "security-review",
            TaskStatus.RUNNING,
            Instant.parse("2026-07-21T10:05:00Z"),
            TargetType.CAPABILITY,
            null,
            "test-tenant",
            "Security review",
            "agent-1",
            null),
        "test-tenant");
  }

  @Test
  void getPlanItems_returns404ForUnknownCase() {
    given()
        .when()
        .get("/api/v1/cases/00000000-0000-0000-0000-000000000001/plan-items")
        .then()
        .statusCode(404)
        .body("status", equalTo(404));
  }

  @Test
  void getPlanItems_returnsItemsForExistingCase() {
    given()
        .when()
        .get("/api/v1/cases/" + caseId + "/plan-items")
        .then()
        .statusCode(200)
        .body("$", hasSize(2))
        .body("[0].planItemId", equalTo("pi-1"))
        .body("[0].bindingName", equalTo("code-analysis"))
        .body("[0].targetType", equalTo("capability"))
        .body("[0].status", equalTo("COMPLETED"))
        .body("[0].description", equalTo("Initial code analysis"))
        .body("[1].planItemId", equalTo("pi-2"))
        .body("[1].bindingName", equalTo("security-review"))
        .body("[1].executorName", equalTo("agent-1"))
        .body("[1].status", equalTo("RUNNING"));
  }

  @Test
  void getPlanItems_returnsEmptyListForCaseWithNoItems() {
    UUID emptyCaseId = UUID.randomUUID();

    CaseMetaModel meta2 = new CaseMetaModel();
    meta2.setNamespace("test");
    meta2.setName("empty-case");
    meta2.setVersion("1.0.0");
    meta2 = metaModelRepository.save(meta2, "test-tenant");

    CaseInstance instance2 = new CaseInstance();
    instance2.setUuid(emptyCaseId);
    instance2.setCaseMetaModel(meta2);
    instance2.setState(CaseStatus.RUNNING);
    instanceRepository.save(instance2, "test-tenant");

    given()
        .when()
        .get("/api/v1/cases/" + emptyCaseId + "/plan-items")
        .then()
        .statusCode(200)
        .body("$", hasSize(0));
  }
}
