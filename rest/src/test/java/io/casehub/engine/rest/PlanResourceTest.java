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

import io.casehub.engine.plan.execution.ExecutionSnapshotStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PlanResourceTest {

  @Inject ExecutionSnapshotStore snapshotStore;

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
}
