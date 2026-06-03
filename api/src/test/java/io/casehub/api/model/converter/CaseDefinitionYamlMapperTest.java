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
package io.casehub.api.model.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.SlaStartFrom;
import io.casehub.api.model.Worker;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperTest {

  @Test
  void load_minimalYaml_loadsSuccessfully() throws IOException {
    String yaml =
        """
        namespace: test
        name: Minimal Case
        version: 1.0.0
        spec:
          capabilities: []
          workers: []
          bindings: []
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getNamespace()).isEqualTo("test");
    assertThat(def.getName()).isEqualTo("Minimal Case");
    assertThat(def.getVersion()).isEqualTo("1.0.0");
    assertThat(def.getCapabilities()).isEmpty();
    assertThat(def.getWorkers()).isEmpty();
    assertThat(def.getBindings()).isEmpty();
  }

  @Test
  void load_fullYaml_convertsAllElements() throws IOException {
    String yaml =
        """
        namespace: test
        name: Full Case
        version: 1.0.0
        title: Complete Test Case
        dsl: 1.0.0
        spec:
          capabilities:
            - name: validate
              inputSchema: ".request"
              outputSchema: ".valid"
              description: Validates input
          workers:
            - name: validator-worker
              capabilities:
                - validate
              description: Worker that validates
          bindings:
            - name: validation-binding
              capability: validate
              on:
                contextChange:
                  filter: ".status == \\"pending\\""
          milestones:
            - name: validation-complete
              condition: ".valid == true"
              description: Validation milestone
          goals:
            - name: process-complete
              condition: ".processed == true"
              description: Processing goal
          completion:
            success:
              allOf:
                - process-complete
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getNamespace()).isEqualTo("test");
    assertThat(def.getName()).isEqualTo("Full Case");
    assertThat(def.getVersion()).isEqualTo("1.0.0");
    assertThat(def.getTitle()).isEqualTo("Complete Test Case");
    assertThat(def.getDsl()).isEqualTo("1.0.0");

    // Capabilities
    assertThat(def.getCapabilities()).hasSize(1);
    Capability cap = def.getCapabilities().get(0);
    assertThat(cap.getName()).isEqualTo("validate");
    assertThat(cap.getInputSchema()).isEqualTo(".request");
    assertThat(cap.getOutputSchema()).isEqualTo(".valid");
    assertThat(cap.getDescription()).isEqualTo("Validates input");

    // Workers
    assertThat(def.getWorkers()).hasSize(1);
    Worker worker = def.getWorkers().get(0);
    assertThat(worker.getName()).isEqualTo("validator-worker");
    assertThat(worker.getCapabilities()).containsExactly(cap);
    assertThat(worker.getDescription()).isEqualTo("Worker that validates");

    // Bindings
    assertThat(def.getBindings()).hasSize(1);
    Binding binding = def.getBindings().get(0);
    assertThat(binding.getName()).isEqualTo("validation-binding");
    assertThat(binding.target()).isInstanceOf(io.casehub.api.model.CapabilityTarget.class);
    assertThat(((io.casehub.api.model.CapabilityTarget) binding.target()).capability())
        .isEqualTo(cap);
    assertThat(binding.getOn()).isInstanceOf(ContextChangeTrigger.class);
    ContextChangeTrigger trigger = (ContextChangeTrigger) binding.getOn();
    assertThat(trigger.getFilter()).isInstanceOf(JQExpressionEvaluator.class);
    JQExpressionEvaluator filter = (JQExpressionEvaluator) trigger.getFilter();
    assertThat(filter.expression()).isEqualTo(".status == \"pending\"");

    // Milestones
    assertThat(def.getMilestones()).hasSize(1);
    Milestone milestone = def.getMilestones().get(0);
    assertThat(milestone.getName()).isEqualTo("validation-complete");
    assertThat(milestone.getCompletionCriteria()).isInstanceOf(JQExpressionEvaluator.class);
    JQExpressionEvaluator milestoneCondition =
        (JQExpressionEvaluator) milestone.getCompletionCriteria();
    assertThat(milestoneCondition.expression()).isEqualTo(".valid == true");
    assertThat(milestone.getDescription()).isEqualTo("Validation milestone");

    // Goals
    assertThat(def.getGoals()).hasSize(1);
    Goal goal = def.getGoals().get(0);
    assertThat(goal.getName()).isEqualTo("process-complete");
    assertThat(goal.getCondition()).isInstanceOf(JQExpressionEvaluator.class);
    JQExpressionEvaluator goalCondition = (JQExpressionEvaluator) goal.getCondition();
    assertThat(goalCondition.expression()).isEqualTo(".processed == true");
    assertThat(goal.getKind()).isEqualTo(GoalKind.SUCCESS);
    assertThat(goal.getDescription()).isEqualTo("Processing goal");

    // Completion
    assertThat(def.getCompletion()).isNotNull();
  }

  @Test
  void load_subCaseBinding_convertsSuccessfully() throws IOException {
    String yaml =
        """
        namespace: test
        name: SubCase Test
        version: 1.0.0
        spec:
          bindings:
            - name: approval-binding
              subCase:
                namespace: approvals
                name: manager-approval
                version: 1.0.0
                waitForCompletion: true
              on:
                contextChange:
                  filter: ".amount > 1000"
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getBindings()).hasSize(1);
    Binding binding = def.getBindings().get(0);
    assertThat(binding.getName()).isEqualTo("approval-binding");
    assertThat(binding.target()).isInstanceOf(io.casehub.api.model.SubCaseTarget.class);
    io.casehub.api.model.SubCase subCase =
        ((io.casehub.api.model.SubCaseTarget) binding.target()).subCase();
    assertThat(subCase.namespace()).isEqualTo("approvals");
    assertThat(subCase.name()).isEqualTo("manager-approval");
    assertThat(subCase.version()).isEqualTo("1.0.0");
    assertThat(subCase.waitForCompletion()).isTrue();
  }

  @Test
  void load_nullInputStream_throwsException() {
    assertThatThrownBy(() -> CaseDefinitionYamlMapper.load(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("InputStream cannot be null");
  }

  @Test
  void load_invalidYaml_throwsIOException() {
    String invalidYaml = "{ this is not valid yaml: [}";
    InputStream is = new ByteArrayInputStream(invalidYaml.getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> CaseDefinitionYamlMapper.load(is)).isInstanceOf(IOException.class);
  }

  @Test
  void load_workerWithAgent_convertsAgentToApiModel() throws IOException {
    String yaml =
        """
        namespace: test
        name: Agent Test
        version: 1.0.0
        spec:
          capabilities:
            - name: analyze
              inputSchema: "{ text: .text }"
              outputSchema: "{ result: .result }"
          workers:
            - name: analyzer-worker
              capabilities:
                - analyze
              agent:
                systemPrompt: "You are an analyzer"
                inputSchema: "{ text: .text }"
                outputSchema: "{ result: .result }"
                model:
                  openai:
                    apiKey: "test-key"
                    modelName: "gpt-4"
                    temperature: 0.7
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getWorkers()).hasSize(1);
    Worker worker = def.getWorkers().get(0);
    assertThat(worker.getName()).isEqualTo("analyzer-worker");
    assertThat(worker.getFunction()).isNotNull();

    // Verify that the agent was converted to API model (Agent is the value in the function holder)
    Object value = worker.getFunction().getValue();
    assertThat(value).isInstanceOf(Agent.class);
  }

  @Test
  void humanTaskBinding_inline_parsedCorrectly() throws IOException {
    String yaml =
        """
        namespace: test
        name: Human Task Case
        version: 1.0.0
        spec:
          bindings:
            - name: approval
              on: { contextChange: {} }
              humanTask:
                title: "PR approval required"
                outputMapping: "{ approval: { status: .decision } }"
        """;

    CaseDefinition def =
        CaseDefinitionYamlMapper.load(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    assertThat(def.getBindings()).hasSize(1);
    Binding binding = def.getBindings().get(0);
    assertThat(binding.getName()).isEqualTo("approval");
    assertThat(binding.target()).isInstanceOf(HumanTaskTarget.class);

    HumanTaskTarget ht = (HumanTaskTarget) binding.target();
    assertThat(ht.isTemplateMode()).isFalse();
    assertThat(ht.title()).isEqualTo("PR approval required");
    assertThat(ht.outputMapping()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) ht.outputMapping()).expression())
        .isEqualTo("{ approval: { status: .decision } }");
    assertThat(ht.inputMapping()).isNull();
    assertThat(ht.candidateGroups()).isNull();
    assertThat(ht.expiresIn()).isNull();
  }

  @Test
  void humanTaskBinding_template_parsedCorrectly() throws IOException {
    String yaml =
        """
        namespace: test
        name: Template Task Case
        version: 1.0.0
        spec:
          bindings:
            - name: review
              on: { contextChange: {} }
              humanTask:
                templateRef: "senior-review"
                outputMapping: "{ review: .outcome }"
        """;

    CaseDefinition def =
        CaseDefinitionYamlMapper.load(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    HumanTaskTarget ht = (HumanTaskTarget) def.getBindings().get(0).target();
    assertThat(ht.isTemplateMode()).isTrue();
    assertThat(ht.templateRef()).isEqualTo("senior-review");
    assertThat(ht.title()).isNull();
    assertThat(ht.outputMapping()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) ht.outputMapping()).expression())
        .isEqualTo("{ review: .outcome }");
  }

  @Test
  void humanTaskBinding_withAllOptionalFields_parsedCorrectly() throws IOException {
    String yaml =
        """
        namespace: test
        name: Full Human Task Case
        version: 1.0.0
        spec:
          bindings:
            - name: full-approval
              on: { contextChange: {} }
              humanTask:
                title: "Full approval task"
                inputMapping: "{ pr: .pr }"
                outputMapping: "{ approval: .decision }"
                candidateGroups:
                  - architects
                  - seniors
                candidateUsers:
                  - alice
                expiresIn: "PT24H"
        """;

    CaseDefinition def =
        CaseDefinitionYamlMapper.load(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    HumanTaskTarget ht = (HumanTaskTarget) def.getBindings().get(0).target();
    assertThat(ht.candidateGroups()).containsExactlyInAnyOrder("architects", "seniors");
    assertThat(ht.candidateUsers()).containsExactlyInAnyOrder("alice");
    assertThat(ht.expiresIn()).isEqualTo(Duration.parse("PT24H"));
    assertThat(ht.inputMapping()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) ht.inputMapping()).expression()).isEqualTo("{ pr: .pr }");
    assertThat(((JQExpressionEvaluator) ht.outputMapping()).expression())
        .isEqualTo("{ approval: .decision }");
  }

  @Test
  void humanTaskBinding_emptyCandidateLists_treatedAsNotSet() throws IOException {
    String yaml =
        """
        namespace: test
        name: Empty Lists Case
        version: 1.0.0
        spec:
          bindings:
            - name: approval
              on: { contextChange: {} }
              humanTask:
                title: "Approval"
                candidateGroups: []
                candidateUsers: []
        """;

    CaseDefinition def =
        CaseDefinitionYamlMapper.load(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    HumanTaskTarget ht = (HumanTaskTarget) def.getBindings().get(0).target();
    assertThat(ht.candidateGroups()).isNull();
    assertThat(ht.candidateUsers()).isNull();
  }

  @Test
  void humanTaskBinding_withBothTitleAndTemplateRef_throwsIllegalArgument() {
    String yaml =
        """
        namespace: test
        name: Conflict Case
        version: 1.0.0
        spec:
          bindings:
            - name: conflict-binding
              on: { contextChange: {} }
              humanTask:
                title: "Inline title"
                templateRef: "some-template"
        """;

    assertThatThrownBy(
            () ->
                CaseDefinitionYamlMapper.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflict-binding")
        .hasMessageContaining("cannot specify both");
  }

  @Test
  void humanTaskBinding_withInvalidExpiresInFormat_throwsIllegalArgument() {
    String yaml =
        """
        namespace: test
        name: Bad Expires Case
        version: 1.0.0
        spec:
          bindings:
            - name: expires-binding
              on: { contextChange: {} }
              humanTask:
                title: "Review"
                expiresIn: "1h"
        """;

    assertThatThrownBy(
            () ->
                CaseDefinitionYamlMapper.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expires-binding")
        .hasMessageContaining("1h");
  }

  @Test
  void humanTaskBinding_withNonPositiveExpiresIn_throwsIllegalArgument() {
    String yaml =
        """
        namespace: test
        name: Zero Expires Case
        version: 1.0.0
        spec:
          bindings:
            - name: zero-binding
              on: { contextChange: {} }
              humanTask:
                title: "Review"
                expiresIn: "PT0S"
        """;

    assertThatThrownBy(
            () ->
                CaseDefinitionYamlMapper.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zero-binding")
        .hasMessageContaining("must be positive");
  }

  @Test
  void humanTaskBinding_withScope_scopePropagatedToTarget() throws IOException {
    String yaml =
        """
        namespace: test
        name: Scoped HumanTask Case
        version: 1.0.0
        spec:
          bindings:
            - name: irb-review
              on: { contextChange: {} }
              humanTask:
                title: "IRB Ethics Review"
                scope: "casehubio/clinical/adverse-event"
        """;

    CaseDefinition def =
        CaseDefinitionYamlMapper.load(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    Binding binding = def.getBindings().get(0);
    HumanTaskTarget target = (HumanTaskTarget) binding.target();
    assertThat(target.scope()).isEqualTo("casehubio/clinical/adverse-event");
  }

  @Test
  void humanTaskBinding_withoutScope_scopeIsNull() throws IOException {
    String yaml =
        """
        namespace: test
        name: Unscoped HumanTask Case
        version: 1.0.0
        spec:
          bindings:
            - name: irb-review
              on: { contextChange: {} }
              humanTask:
                title: "IRB Ethics Review"
        """;

    CaseDefinition def =
        CaseDefinitionYamlMapper.load(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    Binding binding = def.getBindings().get(0);
    HumanTaskTarget target = (HumanTaskTarget) binding.target();
    assertThat(target.scope()).isNull();
  }

  @Test
  void humanTaskBinding_withNegativeExpiresIn_throwsIllegalArgument() {
    String yaml =
        """
        namespace: test
        name: Negative Expires Case
        version: 1.0.0
        spec:
          bindings:
            - name: negative-binding
              on: { contextChange: {} }
              humanTask:
                title: "Review"
                expiresIn: "PT-1H"
        """;

    assertThatThrownBy(
            () ->
                CaseDefinitionYamlMapper.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative-binding")
        .hasMessageContaining("must be positive");
  }

  @Test
  void load_failureGoalAndCompletionFailure_parsedCorrectly() throws IOException {
    String yaml =
        """
        namespace: test
        name: Failure Goal Test
        version: 1.0.0
        spec:
          goals:
            - name: pr-approved
              kind: success
              condition: '.approval.status == "approved"'
            - name: pr-sla-breached
              kind: failure
              condition: '.humanApproval.status == "sla-breach"'
          completion:
            success:
              allOf:
                - pr-approved
            failure:
              anyOf:
                - pr-sla-breached
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getGoals()).hasSize(2);

    Goal successGoal = def.getGoals().get(0);
    assertThat(successGoal.getName()).isEqualTo("pr-approved");
    assertThat(successGoal.getKind()).isEqualTo(GoalKind.SUCCESS);

    Goal failureGoal = def.getGoals().get(1);
    assertThat(failureGoal.getName()).isEqualTo("pr-sla-breached");
    assertThat(failureGoal.getKind()).isEqualTo(GoalKind.FAILURE);

    assertThat(def.getCompletion()).isInstanceOf(GoalBasedCompletion.class);
    GoalBasedCompletion completion = (GoalBasedCompletion) def.getCompletion();
    assertThat(completion.getSuccess()).isNotNull();
    assertThat(completion.getFailure()).isNotNull();
  }

  @Test
  void milestone_withSlaFields_allFieldsParsed() throws IOException {
    String yaml =
        """
        namespace: test
        name: SLA Test
        version: 1.0.0
        spec:
          milestones:
            - name: approval
              condition: '.approved == true'
              entryCriteria: '.submitted == true'
              slaDuration: PT2H
              slaStartFrom: CASE_CREATED
        """;

    CaseDefinition def =
        CaseDefinitionYamlMapper.load(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    assertThat(def.getMilestones()).hasSize(1);
    Milestone m = def.getMilestones().get(0);
    assertThat(m.getName()).isEqualTo("approval");
    assertThat(m.getCompletionCriteria()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) m.getCompletionCriteria()).expression())
        .isEqualTo(".approved == true");
    assertThat(m.getEntryCriteria()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) m.getEntryCriteria()).expression())
        .isEqualTo(".submitted == true");
    assertThat(m.getSlaDuration()).isEqualTo(Duration.ofHours(2));
    assertThat(m.getSlaStartFrom()).isEqualTo(SlaStartFrom.CASE_CREATED);
  }

  @Test
  void milestone_withoutSlaFields_usesDefaults() throws IOException {
    String yaml =
        """
        namespace: test
        name: Defaults Test
        version: 1.0.0
        spec:
          milestones:
            - name: simple
              condition: '.done == true'
        """;

    CaseDefinition def =
        CaseDefinitionYamlMapper.load(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    Milestone m = def.getMilestones().get(0);
    assertThat(m.getName()).isEqualTo("simple");
    assertThat(m.getSlaDuration()).isNull();
    assertThat(m.getSlaStartFrom()).isEqualTo(SlaStartFrom.MILESTONE_ACTIVATED);
    assertThat(m.getEntryCriteria()).isNotNull();
    assertThat(m.getEntryCriteria()).isNotInstanceOf(JQExpressionEvaluator.class);
  }

  @Test
  void milestone_withEntryCriteriaOnly_slaFieldsDefault() throws IOException {
    String yaml =
        """
        namespace: test
        name: Entry Test
        version: 1.0.0
        spec:
          milestones:
            - name: gated
              condition: '.reviewed == true'
              entryCriteria: '.assignee != null'
        """;

    CaseDefinition def =
        CaseDefinitionYamlMapper.load(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    Milestone m = def.getMilestones().get(0);
    assertThat(m.getEntryCriteria()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) m.getEntryCriteria()).expression())
        .isEqualTo(".assignee != null");
    assertThat(m.getSlaDuration()).isNull();
    assertThat(m.getSlaStartFrom()).isEqualTo(SlaStartFrom.MILESTONE_ACTIVATED);
  }

  @Test
  void milestone_withInvalidSlaDurationFormat_throwsIllegalArgument() {
    String yaml =
        """
        namespace: test
        name: Bad Duration
        version: 1.0.0
        spec:
          milestones:
            - name: bad-sla
              condition: '.done == true'
              slaDuration: "1h"
        """;

    assertThatThrownBy(
            () ->
                CaseDefinitionYamlMapper.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bad-sla")
        .hasMessageContaining("1h");
  }

  @Test
  void milestone_withZeroSlaDuration_throwsIllegalArgument() {
    String yaml =
        """
        namespace: test
        name: Zero Duration
        version: 1.0.0
        spec:
          milestones:
            - name: zero-sla
              condition: '.done == true'
              slaDuration: "PT0S"
        """;

    assertThatThrownBy(
            () ->
                CaseDefinitionYamlMapper.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zero-sla")
        .hasMessageContaining("must be positive");
  }

  @Test
  void milestone_withNegativeSlaDuration_throwsIllegalArgument() {
    String yaml =
        """
        namespace: test
        name: Neg Duration
        version: 1.0.0
        spec:
          milestones:
            - name: neg-sla
              condition: '.done == true'
              slaDuration: "PT-1H"
        """;

    assertThatThrownBy(
            () ->
                CaseDefinitionYamlMapper.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("neg-sla")
        .hasMessageContaining("must be positive");
  }

  @Test
  void milestone_withUnimplementedSlaStartFrom_throwsUnsupportedOperation() {
    String yaml =
        """
        namespace: test
        name: Unimplemented StartFrom
        version: 1.0.0
        spec:
          milestones:
            - name: future-sla
              condition: '.done == true'
              slaDuration: PT1H
              slaStartFrom: PREVIOUS_MILESTONE_COMPLETED
        """;

    assertThatThrownBy(
            () ->
                CaseDefinitionYamlMapper.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("future-sla")
        .hasMessageContaining("PREVIOUS_MILESTONE_COMPLETED")
        .hasMessageContaining("not yet implemented");
  }
}
