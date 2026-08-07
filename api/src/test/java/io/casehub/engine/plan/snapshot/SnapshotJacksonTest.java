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
package io.casehub.engine.plan.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnapshotJacksonTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void leafTaskSnapshotSerializesWithKindDiscriminator() throws Exception {
    TaskNodeSnapshot leaf = new LeafTaskSnapshot("id-1", "desc", "agent-1");
    String json = mapper.writeValueAsString(leaf);
    assertThat(json).contains("\"kind\":\"leaf\"");
    assertThat(json).contains("\"id\":\"id-1\"");

    TaskNodeSnapshot deserialized = mapper.readValue(json, TaskNodeSnapshot.class);
    assertThat(deserialized).isInstanceOf(LeafTaskSnapshot.class);
  }

  @Test
  void compoundTaskSnapshotSerializesWithKindDiscriminator() throws Exception {
    TaskNodeSnapshot compound = new CompoundTaskSnapshot("ct-1", "analysis", List.of());
    String json = mapper.writeValueAsString(compound);
    assertThat(json).contains("\"kind\":\"compound\"");

    TaskNodeSnapshot deserialized = mapper.readValue(json, TaskNodeSnapshot.class);
    assertThat(deserialized).isInstanceOf(CompoundTaskSnapshot.class);
  }

  @Test
  void completionSemanticsSnapshotSerializesCorrectly() throws Exception {
    CompletionSemanticsSnapshot all = new CompletionSemanticsSnapshot.AllSnapshot();
    String json = mapper.writeValueAsString(all);
    assertThat(json).contains("\"kind\":\"All\"");

    CompletionSemanticsSnapshot mOfN = new CompletionSemanticsSnapshot.MOfNSnapshot(3);
    json = mapper.writeValueAsString(mOfN);
    assertThat(json).contains("\"kind\":\"MOfN\"");
    assertThat(json).contains("\"m\":3");
  }

  @Test
  void planItemDefinitionSnapshotSerializesWithKind() throws Exception {
    PlanItemDefinitionSnapshot prim =
        new PrimitiveItemSnapshot("p-1", "worker-a", "exec-1", "desc", ".input != null");
    String json = mapper.writeValueAsString(prim);
    assertThat(json).contains("\"kind\":\"primitive\"");

    PlanItemDefinitionSnapshot deserialized =
        mapper.readValue(json, PlanItemDefinitionSnapshot.class);
    assertThat(deserialized).isInstanceOf(PrimitiveItemSnapshot.class);
  }

  @Test
  void compoundItemSnapshotRoundTrips() throws Exception {
    PlanItemDefinitionSnapshot compound =
        new CompoundItemSnapshot(
            "c-1",
            "phase",
            List.of(new PrimitiveItemSnapshot("p-1", "step", "exec", null, null)),
            "sequential",
            new CompletionSemanticsSnapshot.AllSnapshot(),
            "CHOREOGRAPHED",
            null,
            null,
            false,
            Map.of("binding-a", "PARTICIPANT"));
    String json = mapper.writeValueAsString(compound);
    assertThat(json).contains("\"kind\":\"compound\"");

    PlanItemDefinitionSnapshot deserialized =
        mapper.readValue(json, PlanItemDefinitionSnapshot.class);
    assertThat(deserialized).isInstanceOf(CompoundItemSnapshot.class);
    CompoundItemSnapshot cs = (CompoundItemSnapshot) deserialized;
    assertThat(cs.children()).hasSize(1);
    assertThat(cs.scopedBindings()).containsEntry("binding-a", "PARTICIPANT");
  }
}
