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
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseMetaModelRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CaseDefinitionResourceTest {

  @Inject TestCaseDefinitionRegistry registry;
  @Inject CaseMetaModelRepository metaModelRepository;

  @BeforeEach
  void setUp() {
    CaseDefinition definition =
        CaseDefinition.builder()
            .namespace("acme")
            .name("order-processing")
            .version("1.0.0")
            .build();

    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace("acme");
    meta.setName("order-processing");
    meta.setVersion("1.0.0");
    meta = metaModelRepository.save(meta, "test-tenant");

    registry.register(definition, meta);
  }

  @Test
  void listAll_returns200WithPaginatedResults() {
    given()
        .when()
        .get("/api/v1/case-definitions")
        .then()
        .statusCode(200)
        .body("items", hasSize(1))
        .body("page", equalTo(1))
        .body("size", equalTo(20));
  }

  @Test
  void getByKey_returns200ForExistingDefinition() {
    given()
        .when()
        .get("/api/v1/case-definitions/acme/order-processing/1.0.0")
        .then()
        .statusCode(200)
        .body("namespace", equalTo("acme"))
        .body("name", equalTo("order-processing"));
  }

  @Test
  void getByKey_returns404ForUnknownDefinition() {
    given()
        .when()
        .get("/api/v1/case-definitions/unknown/nonexistent/1.0.0")
        .then()
        .statusCode(404)
        .body("status", equalTo(404));
  }

  @Test
  void getByNamespaceAndName_returns200ForExisting() {
    given()
        .when()
        .get("/api/v1/case-definitions/acme/order-processing")
        .then()
        .statusCode(200)
        .body("$", hasSize(1));
  }

  @Test
  void getByNamespaceAndName_returns404ForUnknown() {
    given().when().get("/api/v1/case-definitions/unknown/nonexistent").then().statusCode(404);
  }
}
