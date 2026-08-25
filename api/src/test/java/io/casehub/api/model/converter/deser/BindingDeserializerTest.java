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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.OutcomeAction;
import io.casehub.api.model.SignalTarget;
import io.casehub.api.model.SubCase;
import io.casehub.api.model.SubCaseTarget;
import io.casehub.api.model.converter.CaseDefinitionModule;
import org.junit.jupiter.api.Test;

class BindingDeserializerTest {

  private final ObjectMapper mapper =
      new ObjectMapper().registerModule(new CaseDefinitionModule(null));

  @Test
  void capabilityBinding_deserializes() throws Exception {
    String json =
        """
        {
          "name": "trigger",
          "capability": "process",
          "on": {"contextChange": {"filter": ".ready"}}
        }
        """;
    Binding result = mapper.readValue(json, Binding.class);
    assertEquals("trigger", result.getName());
    assertInstanceOf(CapabilityTarget.class, result.target());
    assertInstanceOf(ContextChangeTrigger.class, result.getOn());
    assertEquals("process", ((CapabilityTarget) result.target()).capability().name());
  }

  @Test
  void humanTaskBinding_deserializes() throws Exception {
    String json =
        """
        {
          "name": "review",
          "humanTask": {
            "title": "Review Task",
            "candidateGroups": ["reviewers"]
          },
          "on": {"contextChange": {}}
        }
        """;
    Binding result = mapper.readValue(json, Binding.class);
    assertEquals("review", result.getName());
    assertInstanceOf(HumanTaskTarget.class, result.target());
    HumanTaskTarget ht = (HumanTaskTarget) result.target();
    assertNotNull(ht.candidateGroups());
  }

  @Test
  void subCaseBinding_deserializes() throws Exception {
    String json =
        """
        {
          "name": "child",
          "subCase": {
            "namespace": "test",
            "name": "child-case",
            "version": "1.0.0"
          },
          "on": {"contextChange": {}}
        }
        """;
    Binding result = mapper.readValue(json, Binding.class);
    assertInstanceOf(SubCaseTarget.class, result.target());
    SubCase subCase = ((SubCaseTarget) result.target()).subCase();
    assertEquals("child-case", subCase.name());
    assertEquals("test", subCase.namespace());
  }

  @Test
  void signalBinding_deserializes() throws Exception {
    String json =
        """
        {
          "name": "notify",
          "signal": {"key": "value"},
          "on": {"schedule": {"cron": "0 0 * * *"}}
        }
        """;
    Binding result = mapper.readValue(json, Binding.class);
    assertInstanceOf(SignalTarget.class, result.target());
    assertEquals("value", ((SignalTarget) result.target()).payload().get("key"));
  }

  @Test
  void whenCondition_deserializes() throws Exception {
    String json =
        """
        {
          "name": "guarded",
          "capability": "process",
          "on": {"contextChange": {}},
          "when": ".amount > 1000"
        }
        """;
    Binding result = mapper.readValue(json, Binding.class);
    assertNotNull(result.getWhen());
  }

  @Test
  void outcomePolicy_deserializes() throws Exception {
    String json =
        """
        {
          "name": "strict",
          "capability": "process",
          "on": {"contextChange": {}},
          "outcomePolicy": {
            "onDecline": "FAULT",
            "onFailure": "REROUTE",
            "maxRerouteAttempts": 5
          }
        }
        """;
    Binding result = mapper.readValue(json, Binding.class);
    assertNotNull(result.getOutcomePolicy());
    assertEquals(OutcomeAction.FAULT, result.getOutcomePolicy().onDecline());
    assertEquals(OutcomeAction.REROUTE, result.getOutcomePolicy().onFailure());
    assertEquals(5, result.getOutcomePolicy().maxRerouteAttempts());
  }

  @Test
  void producedKeys_deserializes() throws Exception {
    String json =
        """
        {
          "name": "producer",
          "capability": "process",
          "on": {"contextChange": {}},
          "producedKeys": ["result", "status"]
        }
        """;
    Binding result = mapper.readValue(json, Binding.class);
    assertNotNull(result.getProducedKeys());
    assertEquals(2, result.getProducedKeys().size());
  }

  @Test
  void contingency_deserializes() throws Exception {
    String json =
        """
        {
          "name": "fallback",
          "capability": "process",
          "on": {"contextChange": {}},
          "contingency": ["manual-review", "escalate"]
        }
        """;
    Binding result = mapper.readValue(json, Binding.class);
    assertNotNull(result.getContingency());
    assertEquals(2, result.getContingency().size());
  }
}
