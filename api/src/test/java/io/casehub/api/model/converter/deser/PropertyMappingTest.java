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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseDefinitionSpec;
import io.casehub.api.model.converter.CaseDefinitionModule;
import org.junit.jupiter.api.Test;

class PropertyMappingTest {

  private final ObjectMapper mapper =
      new ObjectMapper()
          .registerModule(new CaseDefinitionModule(null))
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  @Test
  void mixins_registered() {
    assertNotNull(mapper.findMixInClassFor(CaseDefinitionSpec.class));
    assertNotNull(mapper.findMixInClassFor(io.casehub.api.model.CaseDefinition.class));
    assertNotNull(mapper.findMixInClassFor(io.casehub.engine.plan.goap.GoapAction.class));
  }

  @Test
  void adaptationYamlKey_mapsToAdaptationConfigField() throws Exception {
    String json =
        "{\"adaptation\": {\"trigger\": \"every-step\", \"optimization\": \"forward-replan\"}}";
    CaseDefinitionSpec spec = mapper.readValue(json, CaseDefinitionSpec.class);
    assertNotNull(spec.getAdaptationConfig());
  }

  @Test
  void reflectionYamlKey_mapsToReflectionTriggerField() throws Exception {
    String json =
        "{\"reflection\": {\"enabled\": true, \"importanceThreshold\": 3.0, \"maxUnreflectedOutcomes\": 5, \"maxSourceMemories\": 10}}";
    CaseDefinitionSpec spec = mapper.readValue(json, CaseDefinitionSpec.class);
    assertNotNull(spec.getReflectionTrigger());
  }

  @Test
  void monitoringYamlKey_mapsToMonitoringConfigField() throws Exception {
    String json =
        "{\"monitoring\": {\"enabled\": true, \"perCompletionThreshold\": 0.5, \"windowSize\": 5}}";
    CaseDefinitionSpec spec = mapper.readValue(json, CaseDefinitionSpec.class);
    assertNotNull(spec.getMonitoringConfig());
  }

  @Test
  void goapAction_costFunctionIgnored() throws Exception {
    String json =
        "{\"name\": \"act\", \"preconditions\": {}, \"effects\": {\"done\": true}, \"cost\": 1.0, \"benefit\": 0, \"softPreconditions\": {}}";
    io.casehub.engine.plan.goap.GoapAction action =
        mapper.readValue(json, io.casehub.engine.plan.goap.GoapAction.class);
    assertNotNull(action);
    assertTrue(action.effects().containsKey("done"));
  }
}
