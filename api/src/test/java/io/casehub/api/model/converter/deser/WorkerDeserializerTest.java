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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.converter.CaseDefinitionModule;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import org.junit.jupiter.api.Test;

class WorkerDeserializerTest {

  private final ObjectMapper mapper =
      new ObjectMapper().registerModule(new CaseDefinitionModule(null));

  @Test
  void basicWorker_deserializes() throws Exception {
    String json = "{\"name\": \"analyser\", \"capabilities\": [\"analysis\"]}";
    Worker result = mapper.readValue(json, Worker.class);
    assertEquals("analyser", result.name());
    assertTrue(result.capabilities().contains("analysis"));
    assertEquals(WorkerFunction.NONE, result.function());
  }

  @Test
  void workerWithDescription_deserializes() throws Exception {
    String json = "{\"name\": \"w\", \"capabilities\": [\"c\"], \"description\": \"A worker\"}";
    Worker result = mapper.readValue(json, Worker.class);
    assertEquals("A worker", result.description());
  }

  @Test
  void workerWithMultipleCapabilities() throws Exception {
    String json = "{\"name\": \"w\", \"capabilities\": [\"a\", \"b\", \"c\"]}";
    Worker result = mapper.readValue(json, Worker.class);
    assertEquals(3, result.capabilities().size());
    assertTrue(result.capabilities().contains("a"));
    assertTrue(result.capabilities().contains("b"));
    assertTrue(result.capabilities().contains("c"));
  }

  @Test
  void workerWithExecutionPolicy() throws Exception {
    String json =
        "{\"name\": \"w\", \"capabilities\": [\"c\"],"
            + " \"executionPolicy\": {\"timeoutMs\": 5000}}";
    Worker result = mapper.readValue(json, Worker.class);
    assertNotNull(result.executionPolicy());
  }
}
