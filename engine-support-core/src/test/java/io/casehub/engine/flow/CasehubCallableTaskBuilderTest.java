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
package io.casehub.engine.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.serverlessworkflow.api.types.CallFunction;
import io.serverlessworkflow.api.types.FunctionArguments;
import io.serverlessworkflow.api.types.TaskBase;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowMutablePosition;
import io.serverlessworkflow.impl.executors.CallableTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CasehubCallableTaskBuilder}: {@code accept()}, {@code init()}, and that
 * {@code build()} returns a non-null callable. The callable's dispatch behaviour (which uses Arc
 * CDI lookup) is covered by the flow integration test.
 */
class CasehubCallableTaskBuilderTest {

  private CasehubCallableTaskBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new CasehubCallableTaskBuilder();
  }

  // ---- accept() -------------------------------------------------------

  @Test
  void accept_returns_true_for_CallFunction() {
    assertThat(builder.accept(CallFunction.class)).isTrue();
  }

  @Test
  void accept_returns_false_for_non_CallFunction_types() {
    assertThat(builder.accept(TaskBase.class)).isFalse();
  }

  // ---- init() ---------------------------------------------------------

  @Test
  void init_stores_call_name_and_args() {
    builder.init(
        dispatchTask("casehub:dispatch", "analyze-document"),
        mock(WorkflowDefinition.class),
        mock(WorkflowMutablePosition.class));
  }

  @Test
  void init_accepts_any_call_name() {
    final CallFunction task = new CallFunction();
    task.setCall("desiredstate:dispatch");
    final FunctionArguments args = new FunctionArguments();
    args.setAdditionalProperty("nodeId", "node-1");
    task.setWith(args);

    builder.init(task, mock(WorkflowDefinition.class), mock(WorkflowMutablePosition.class));
  }

  @Test
  void init_with_null_with_block_stores_empty_args() {
    final CallFunction task = new CallFunction();
    task.setCall("casehub:dispatch");

    var factory =
        builder.init(task, mock(WorkflowDefinition.class), mock(WorkflowMutablePosition.class));

    final CallableTask callable = factory.get();
    assertThat(callable).isNotNull();
  }

  // ---- build() --------------------------------------------------------

  @Test
  void init_returns_factory_that_produces_callable() {
    var factory =
        builder.init(
            dispatchTask("casehub:dispatch", "generate-report"),
            mock(WorkflowDefinition.class),
            mock(WorkflowMutablePosition.class));

    final CallableTask callable = factory.get();
    assertThat(callable).isNotNull();
  }

  @Test
  void successive_init_calls_produce_independent_factories() {
    var factory1 =
        builder.init(
            dispatchTask("casehub:dispatch", "cap-1"),
            mock(WorkflowDefinition.class),
            mock(WorkflowMutablePosition.class));
    assertThat(factory1.get()).isNotNull();

    var factory2 =
        builder.init(
            dispatchTask("casehub:dispatch", "cap-2"),
            mock(WorkflowDefinition.class),
            mock(WorkflowMutablePosition.class));
    assertThat(factory2.get()).isNotNull();
  }

  // ---- helpers --------------------------------------------------------

  @Test
  void definitionRef_in_with_block_passes_through_as_args() {
    final CallFunction task = new CallFunction();
    task.setCall("casehub:dispatch");
    final FunctionArguments args = new FunctionArguments();
    args.setAdditionalProperty("capability", "forensics");
    args.setAdditionalProperty("definitionRef", "cases/forensics-case.yaml");
    task.setWith(args);

    assertThat(task.getWith().getAdditionalProperties())
        .containsEntry("definitionRef", "cases/forensics-case.yaml")
        .containsEntry("capability", "forensics");
  }

  private static CallFunction dispatchTask(final String callName, final String capability) {
    final CallFunction task = new CallFunction();
    task.setCall(callName);
    final FunctionArguments args = new FunctionArguments();
    args.setAdditionalProperty("capability", capability);
    task.setWith(args);
    return task;
  }
}
