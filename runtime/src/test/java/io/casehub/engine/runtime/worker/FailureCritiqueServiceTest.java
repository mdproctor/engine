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
package io.casehub.engine.internal.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.FailureCategory;
import io.casehub.api.model.FailureDiagnosis;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FailureCritiqueServiceTest {

  private final CaseDefinition definition =
      CaseDefinition.builder().namespace("test").name("test").version("1.0").build();
  private final JsonNode workingLayer = JsonNodeFactory.instance.objectNode();

  @Test
  void transientFailureReturnsReasonDirectly() {
    var service = new FailureCritiqueService(java.util.Optional.empty());
    var diag =
        FailureDiagnosis.of(
            new FailureCategory.Transient("connection timeout"), "w1", "EXPIRED", Instant.now());
    assertEquals("connection timeout", service.generateCritique(diag, workingLayer, definition));
  }

  @Test
  void infeasibleFailureReturnsReasonDirectly() {
    var service = new FailureCritiqueService(java.util.Optional.empty());
    var diag =
        FailureDiagnosis.of(
            new FailureCategory.Infeasible("all agents exhausted"), "w1", "FAILED", Instant.now());
    assertEquals("all agents exhausted", service.generateCritique(diag, workingLayer, definition));
  }

  @Test
  void knowledgeFailureWithoutChatModelReturnsReason() {
    var service = new FailureCritiqueService(java.util.Optional.empty());
    var diag =
        FailureDiagnosis.of(
            new FailureCategory.Knowledge("missing data", "accountId"),
            "w1",
            "DECLINED",
            Instant.now());
    assertEquals("missing data", service.generateCritique(diag, workingLayer, definition));
  }

  @Test
  void nullWorkingLayerHandledGracefully() {
    var service = new FailureCritiqueService(java.util.Optional.empty());
    var diag =
        FailureDiagnosis.of(
            new FailureCategory.Knowledge("missing data", null), "w1", "DECLINED", Instant.now());
    assertEquals("missing data", service.generateCritique(diag, null, definition));
  }
}
