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
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ScheduleTrigger;
import io.casehub.api.model.ScopeActivatedTrigger;
import io.casehub.api.model.Trigger;
import io.casehub.api.model.converter.CaseDefinitionModule;
import org.junit.jupiter.api.Test;

class TriggerDeserializerTest {

  private final ObjectMapper mapper =
      new ObjectMapper().registerModule(new CaseDefinitionModule(null));

  @Test
  void contextChange_withFilter() throws Exception {
    String json = "{\"contextChange\": {\"filter\": \".amount > 100\"}}";
    Trigger result = mapper.readValue(json, Trigger.class);
    assertInstanceOf(ContextChangeTrigger.class, result);
    assertNotNull(((ContextChangeTrigger) result).getFilter());
  }

  @Test
  void contextChange_empty() throws Exception {
    String json = "{\"contextChange\": {}}";
    Trigger result = mapper.readValue(json, Trigger.class);
    assertInstanceOf(ContextChangeTrigger.class, result);
    assertNull(((ContextChangeTrigger) result).getFilter());
  }

  @Test
  void schedule_cron() throws Exception {
    String json = "{\"schedule\": {\"cron\": \"0 0 * * *\"}}";
    Trigger result = mapper.readValue(json, Trigger.class);
    assertInstanceOf(ScheduleTrigger.class, result);
  }

  @Test
  void schedule_every() throws Exception {
    String json = "{\"schedule\": {\"every\": \"PT1H\"}}";
    Trigger result = mapper.readValue(json, Trigger.class);
    assertInstanceOf(ScheduleTrigger.class, result);
  }

  @Test
  void scopeActivated() throws Exception {
    String json = "{\"scopeActivated\": {}}";
    Trigger result = mapper.readValue(json, Trigger.class);
    assertInstanceOf(ScopeActivatedTrigger.class, result);
  }

  @Test
  void unknownKey_throws() {
    assertThrows(Exception.class, () -> mapper.readValue("{\"cloudEvent\": {}}", Trigger.class));
  }

  @Test
  void multipleKeys_throws() {
    assertThrows(
        Exception.class,
        () -> mapper.readValue("{\"contextChange\": {}, \"schedule\": {}}", Trigger.class));
  }

  @Test
  void nullValue_deserializesToNull() throws Exception {
    Trigger result = mapper.readValue("null", Trigger.class);
    assertNull(result);
  }
}
