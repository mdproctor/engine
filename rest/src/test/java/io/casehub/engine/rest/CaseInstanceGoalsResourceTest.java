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
import static org.hamcrest.Matchers.nullValue;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.CaseMetaModelRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CaseInstanceGoalsResourceTest {

  @Inject CaseInstanceRepository instanceRepository;
  @Inject CaseMetaModelRepository metaModelRepository;
  @Inject TestCaseDefinitionRegistry definitionRegistry;
  @Inject TestCaseHubRuntime testRuntime;

  private UUID caseId;

  @BeforeEach
  void setUp() {
    caseId = UUID.randomUUID();

    Goal success =
        Goal.builder()
            .name("all-checks-pass")
            .kind(GoalKind.SUCCESS)
            .condition(ctx -> "APPROVED".equals(ctx.getPath("review.outcome")))
            .build();

    Goal failure =
        Goal.builder()
            .name("review-rejected")
            .kind(GoalKind.FAILURE)
            .condition(ctx -> "REJECTED".equals(ctx.getPath("review.outcome")))
            .build();

    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("goal-test")
            .version("1.0.0")
            .goals(success, failure)
            .completion(GoalExpression.allOf(success), GoalExpression.anyOf(failure))
            .build();

    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace("test");
    meta.setName("goal-test");
    meta.setVersion("1.0.0");
    meta = metaModelRepository.save(meta, "test-tenant");
    definitionRegistry.register(def, meta);

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setCaseMetaModel(meta);
    instance.setState(CaseStatus.RUNNING);
    instanceRepository.save(instance, "test-tenant");

    testRuntime.setContext(caseId, Map.of("review", Map.of("outcome", "APPROVED")));
  }

  @Test
  void getGoals_returns404ForUnknownCase() {
    given()
        .when()
        .get("/api/v1/cases/00000000-0000-0000-0000-000000000001/goals")
        .then()
        .statusCode(404)
        .body("status", equalTo(404));
  }

  @Test
  void getGoals_evaluatesLambdaGoalsAndReturnsStatus() {
    given()
        .when()
        .get("/api/v1/cases/" + caseId + "/goals")
        .then()
        .statusCode(200)
        .body("goals", hasSize(2))
        .body("goals[0].name", equalTo("all-checks-pass"))
        .body("goals[0].kind", equalTo("success"))
        .body("goals[0].satisfied", equalTo(true))
        .body("goals[0].condition", nullValue())
        .body("goals[0].error", nullValue())
        .body("goals[1].name", equalTo("review-rejected"))
        .body("goals[1].kind", equalTo("failure"))
        .body("goals[1].satisfied", equalTo(false))
        .body("completion.type", equalTo("goal-based"))
        .body("completion.satisfied", nullValue())
        .body("completion.byKind.success.satisfied", equalTo(true))
        .body("completion.byKind.success.expressionType", equalTo("allOf"))
        .body("completion.byKind.failure.satisfied", equalTo(false))
        .body("completion.byKind.failure.expressionType", equalTo("anyOf"));
  }

  @Test
  void getGoals_noCompletionConfigured() {
    UUID caseId2 = UUID.randomUUID();

    CaseDefinition def2 =
        CaseDefinition.builder().namespace("test").name("no-completion").version("1.0.0").build();

    CaseMetaModel meta2 = new CaseMetaModel();
    meta2.setNamespace("test");
    meta2.setName("no-completion");
    meta2.setVersion("1.0.0");
    meta2 = metaModelRepository.save(meta2, "test-tenant");
    definitionRegistry.register(def2, meta2);

    CaseInstance instance2 = new CaseInstance();
    instance2.setUuid(caseId2);
    instance2.setCaseMetaModel(meta2);
    instance2.setState(CaseStatus.RUNNING);
    instanceRepository.save(instance2, "test-tenant");

    testRuntime.setContext(caseId2, Map.of());

    given()
        .when()
        .get("/api/v1/cases/" + caseId2 + "/goals")
        .then()
        .statusCode(200)
        .body("goals", hasSize(0))
        .body("completion", nullValue());
  }
}
