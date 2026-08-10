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
package io.casehub.engine.plan.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentResultRecordTest {

  @Test
  void constructionPreservesFields() {
    var record = new AgentResultRecord("agent-1", Map.of("key", "value"), 1500L, "SUCCESS");
    assertThat(record.agentId()).isEqualTo("agent-1");
    assertThat(record.output()).isEqualTo(Map.of("key", "value"));
    assertThat(record.durationMs()).isEqualTo(1500L);
    assertThat(record.status()).isEqualTo("SUCCESS");
  }

  @Test
  void factoryMethodCreatesRecord() {
    var record = AgentResultRecord.of("agent-1", Map.of("result", "ok"), 2000L, "SUCCESS");
    assertThat(record.agentId()).isEqualTo("agent-1");
    assertThat(record.durationMs()).isEqualTo(2000L);
  }

  @Test
  void nullOutputIsAllowed() {
    var record = new AgentResultRecord("agent-1", null, 0L, "FAILURE");
    assertThat(record.output()).isNull();
  }
}
