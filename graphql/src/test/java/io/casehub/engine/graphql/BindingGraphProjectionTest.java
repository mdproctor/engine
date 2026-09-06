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
import io.casehub.engine.graphql.dto.BindingGraphType;
import io.casehub.engine.graphql.dto.EdgeKind;
import io.casehub.worker.api.Capability;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BindingGraphProjectionTest {

  @Test
  void compensationEdges() {
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

    BindingGraphType graph = BindingGraphProjection.project(List.of(forward, compensating));

    assertThat(graph.nodes()).hasSize(2);
    assertThat(graph.edges()).hasSize(1);
    assertThat(graph.edges().get(0).edgeType()).isEqualTo(EdgeKind.COMPENSATION);
    assertThat(graph.edges().get(0).sourceBinding()).isEqualTo("irb-review");
    assertThat(graph.edges().get(0).targetBinding()).isEqualTo("irb-reversal");
    assertThat(graph.edges().get(0).label()).isNull();
    assertThat(graph.compensationGaps()).isEmpty();
  }

  @Test
  void compensationGaps() {
    Binding noCompensation =
        Binding.builder()
            .name("data-export")
            .capability(Capability.of("exportService.export", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .build();

    BindingGraphType graph = BindingGraphProjection.project(List.of(noCompensation));

    assertThat(graph.nodes()).hasSize(1);
    assertThat(graph.nodes().get(0).targetType()).isEqualTo("capability");
    assertThat(graph.edges()).isEmpty();
    assertThat(graph.compensationGaps()).containsExactly("data-export");
  }

  @Test
  void compensationOnlyBindingNotAGap() {
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

    BindingGraphType graph = BindingGraphProjection.project(List.of(compensatingOnly, forward));

    assertThat(graph.compensationGaps()).isEmpty();
    assertThat(graph.nodes()).extracting("compensationOnly").containsExactly(true, false);
  }

  @Test
  void dataFlowEdges() {
    Binding producer =
        Binding.builder()
            .name("ingestion")
            .capability(Capability.of("ingest.run", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .produces("orders-channel")
            .build();
    Binding consumer =
        Binding.builder()
            .name("processing")
            .capability(Capability.of("process.run", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .consumes("orders-channel")
            .build();

    BindingGraphType graph = BindingGraphProjection.project(List.of(producer, consumer));

    assertThat(graph.edges()).hasSize(1);
    assertThat(graph.edges().get(0).edgeType()).isEqualTo(EdgeKind.DATA_FLOW);
    assertThat(graph.edges().get(0).sourceBinding()).isEqualTo("ingestion");
    assertThat(graph.edges().get(0).targetBinding()).isEqualTo("processing");
    assertThat(graph.edges().get(0).label()).isEqualTo("orders-channel");
  }

  @Test
  void dataFlowNoMatchingConsumer() {
    Binding producer =
        Binding.builder()
            .name("orphan-producer")
            .capability(Capability.of("orphan.run", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .produces("nowhere")
            .build();

    BindingGraphType graph = BindingGraphProjection.project(List.of(producer));

    assertThat(graph.edges()).isEmpty();
  }

  @Test
  void triggerDependencyEdges() {
    Binding writer =
        Binding.builder()
            .name("entity-resolver")
            .capability(Capability.of("resolve.entity", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .producedKeys(Set.of("entityResolution", "confidence"))
            .build();
    Binding reader =
        Binding.builder()
            .name("fraud-check")
            .capability(Capability.of("fraud.check", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .requiredKeys(Set.of("entityResolution"))
            .build();

    BindingGraphType graph = BindingGraphProjection.project(List.of(writer, reader));

    var triggerEdges =
        graph.edges().stream().filter(e -> e.edgeType() == EdgeKind.TRIGGER_DEPENDENCY).toList();
    assertThat(triggerEdges).hasSize(1);
    assertThat(triggerEdges.get(0).sourceBinding()).isEqualTo("entity-resolver");
    assertThat(triggerEdges.get(0).targetBinding()).isEqualTo("fraud-check");
    assertThat(triggerEdges.get(0).label()).isEqualTo("entityResolution");
  }

  @Test
  void triggerDependencyNoSelfEdge() {
    Binding selfRef =
        Binding.builder()
            .name("self-ref")
            .capability(Capability.of("self.run", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .producedKeys(Set.of("key"))
            .requiredKeys(Set.of("key"))
            .build();

    BindingGraphType graph = BindingGraphProjection.project(List.of(selfRef));

    assertThat(graph.edges().stream().filter(e -> e.edgeType() == EdgeKind.TRIGGER_DEPENDENCY))
        .isEmpty();
  }

  @Test
  void mixedEdgeTypes() {
    Binding a =
        Binding.builder()
            .name("step-a")
            .capability(Capability.of("a.run", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .produces("data-pipe")
            .producedKeys(Set.of("resultA"))
            .compensateRef("undo-a")
            .build();
    Binding b =
        Binding.builder()
            .name("step-b")
            .capability(Capability.of("b.run", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .consumes("data-pipe")
            .requiredKeys(Set.of("resultA"))
            .build();
    Binding undoA =
        Binding.builder()
            .name("undo-a")
            .capability(Capability.of("a.undo", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .compensation(true)
            .build();

    BindingGraphType graph = BindingGraphProjection.project(List.of(a, b, undoA));

    assertThat(graph.nodes()).hasSize(3);
    assertThat(graph.edges()).hasSize(3);
    assertThat(graph.edges().stream().map(e -> e.edgeType()))
        .containsExactlyInAnyOrder(
            EdgeKind.COMPENSATION, EdgeKind.DATA_FLOW, EdgeKind.TRIGGER_DEPENDENCY);
    assertThat(graph.compensationGaps()).containsExactly("step-b");
  }

  @Test
  void emptyBindings() {
    BindingGraphType graph = BindingGraphProjection.project(List.of());

    assertThat(graph.nodes()).isEmpty();
    assertThat(graph.edges()).isEmpty();
    assertThat(graph.compensationGaps()).isEmpty();
  }

  @Test
  void targetTypeMapping() {
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

    BindingGraphType graph = BindingGraphProjection.project(List.of(cap, jt));

    assertThat(graph.nodes()).extracting("targetType").containsExactly("capability", "judgment");
  }
}
