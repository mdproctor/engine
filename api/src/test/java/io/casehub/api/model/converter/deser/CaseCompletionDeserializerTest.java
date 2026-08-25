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
package io.casehub.api.model.converter.deser;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseCompletion;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.PredicateBasedCompletion;
import io.casehub.api.model.converter.CaseDefinitionModule;
import org.junit.jupiter.api.Test;

class CaseCompletionDeserializerTest {

  private final ObjectMapper mapper =
      new ObjectMapper().registerModule(new CaseDefinitionModule(null));

  @Test
  void doneWhen_deserializesToPredicateBased() throws Exception {
    String json = "{\"doneWhen\": \".status == \\\"done\\\"\"}";
    CaseCompletion result = mapper.readValue(json, CaseCompletion.class);
    assertInstanceOf(PredicateBasedCompletion.class, result);
    assertNotNull(((PredicateBasedCompletion) result).getDoneWhen());
  }

  @Test
  void goalKindMap_deserializesToGoalBased() throws Exception {
    String json = "{\"success\": \"goal-a\", \"failure\": \"goal-b\"}";
    CaseCompletion result = mapper.readValue(json, CaseCompletion.class);
    assertInstanceOf(GoalBasedCompletion.class, result);
    GoalBasedCompletion<?> gbc = (GoalBasedCompletion<?>) result;
    assertNotNull(gbc.getGoals().get(io.casehub.api.model.StandardGoalKind.SUCCESS));
    assertNotNull(gbc.getGoals().get(io.casehub.api.model.StandardGoalKind.FAILURE));
  }

  @Test
  void doneWhenPlusGoalKind_throws() {
    String json = "{\"doneWhen\": \".x\", \"success\": \"g\"}";
    assertThrows(Exception.class, () -> mapper.readValue(json, CaseCompletion.class));
  }

  @Test
  void nullValue_deserializesToNull() throws Exception {
    CaseCompletion result = mapper.readValue("null", CaseCompletion.class);
    assertNull(result);
  }
}
