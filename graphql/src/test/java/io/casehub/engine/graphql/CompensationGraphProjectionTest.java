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
package io.casehub.engine.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.Binding;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.engine.graphql.dto.CompensationGraphType;
import io.casehub.worker.api.Capability;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompensationGraphProjectionTest {

  @Test
  void projectFullCoverage() {
    Binding forward =
        Binding.builder()
            .name("irb-review")
            .judgment(JudgmentTarget.builder().prompt("IRB Review").title("IRB Review").build())
            .on(new ContextChangeTrigger("$"))
            .compensateRef("irb-reversal")
            .build();
    Binding compensating =
        Binding.builder()
            .name("irb-reversal")
            .judgment(JudgmentTarget.builder().prompt("Reverse IRB").title("Reverse IRB").build())
            .on(new ContextChangeTrigger("$"))
            .compensation(true)
            .build();

    CompensationGraphType graph =
        CompensationGraphProjection.project(List.of(forward, compensating));

    assertThat(graph.nodes()).hasSize(2);
    assertThat(graph.edges()).hasSize(1);
    assertThat(graph.edges().get(0).sourceBinding()).isEqualTo("irb-review");
    assertThat(graph.edges().get(0).compensatingBinding()).isEqualTo("irb-reversal");
    assertThat(graph.gaps()).isEmpty();
  }

  @Test
  void projectDetectsGaps() {
    Binding withoutCompensation =
        Binding.builder()
            .name("data-export")
            .capability(Capability.of("exportService.export", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .build();

    CompensationGraphType graph = CompensationGraphProjection.project(List.of(withoutCompensation));

    assertThat(graph.nodes()).hasSize(1);
    assertThat(graph.nodes().get(0).targetType()).isEqualTo("capability");
    assertThat(graph.edges()).isEmpty();
    assertThat(graph.gaps()).containsExactly("data-export");
  }

  @Test
  void projectCompensationOnlyBindingNotAGap() {
    Binding compensatingOnly =
        Binding.builder()
            .name("cleanup")
            .capability(Capability.of("cleanupService.run", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .compensation(true)
            .build();
    Binding forward =
        Binding.builder()
            .name("process")
            .capability(Capability.of("processService.run", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .compensateRef("cleanup")
            .build();

    CompensationGraphType graph =
        CompensationGraphProjection.project(List.of(compensatingOnly, forward));

    assertThat(graph.gaps()).isEmpty();
    assertThat(graph.nodes()).extracting("compensationOnly").containsExactly(true, false);
  }

  @Test
  void projectEmptyBindings() {
    CompensationGraphType graph = CompensationGraphProjection.project(List.of());

    assertThat(graph.nodes()).isEmpty();
    assertThat(graph.edges()).isEmpty();
    assertThat(graph.gaps()).isEmpty();
  }

  @Test
  void projectTargetTypeMapping() {
    Binding cap =
        Binding.builder()
            .name("a")
            .capability(Capability.of("x", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .build();
    Binding jt =
        Binding.builder()
            .name("b")
            .judgment(JudgmentTarget.builder().prompt("p").title("t").build())
            .on(new ContextChangeTrigger("$"))
            .build();

    CompensationGraphType graph = CompensationGraphProjection.project(List.of(cap, jt));

    assertThat(graph.nodes()).extracting("targetType").containsExactly("capability", "judgment");
  }
}
