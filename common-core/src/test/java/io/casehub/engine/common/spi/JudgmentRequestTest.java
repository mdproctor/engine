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
package io.casehub.engine.common.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.casehub.api.model.JudgmentTarget;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JudgmentRequestTest {

  @Test
  void requestWithBindingPayload() {
    var target = JudgmentTarget.builder().prompt("Review").build();
    var payload = new JudgmentPayload.BindingPayload(target, Map.of("data", "value"), null, null);
    var caseId = UUID.randomUUID();

    var request = new JudgmentRequest(caseId, "tenant-1", "review-binding", payload);

    assertEquals(caseId, request.caseId());
    assertEquals("tenant-1", request.tenancyId());
    assertEquals("review-binding", request.bindingName());
    assertInstanceOf(JudgmentPayload.BindingPayload.class, request.payload());
  }
}
