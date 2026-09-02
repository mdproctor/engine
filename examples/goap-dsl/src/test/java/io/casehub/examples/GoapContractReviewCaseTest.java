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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.casehub.api.model.CaseDefinition;
import org.junit.jupiter.api.Test;

class GoapContractReviewCaseTest {

  @Test
  void definesGoapCase() {
    CaseDefinition def = GoapContractReviewCase.define();

    assertEquals("legal", def.getNamespace());
    assertEquals("contract-review", def.getName());
    assertEquals("goap", def.getDecompositionStrategy());
    assertEquals(4, def.getCapabilities().size());
    assertEquals(3, def.getWorkers().size());
    assertEquals(3, def.getBindings().size());
    assertEquals(3, def.getGoapActions().size());
    assertEquals(1, def.getGoals().size());
    assertNotNull(def.getCompletion());
  }
}
