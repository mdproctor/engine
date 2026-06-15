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
package io.casehub.engine.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.context.CaseContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.PredicateBasedCompletion;
import io.casehub.api.model.evaluator.ExpressionEvaluator;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for model builder contracts, equals/hashCode, and GoalKind.fromValue().
 *
 * <p>Builders use {@link java.util.Objects#requireNonNull} for mandatory fields — a NPE here
 * indicates a programming error in the caller, not a runtime condition, so asserting that the
 * exception is thrown is the right contract test.
 */
@DisplayName("Model Builders and Value Objects")
class ModelBuilderTest {

  // ================================================================== //
  //  CaseDefinition                                                     //
  // ================================================================== //

  @Nested
  @DisplayName("CaseDefinition builder")
  class CaseDefinitionBuilderTests {

    @Test
    @DisplayName("null namespace throws NullPointerException")
    void nullNamespace_throws() {
      assertThrows(
          NullPointerException.class,
          () -> CaseDefinition.builder().namespace(null).name("test").version("1.0").build());
    }

    @Test
    @DisplayName("missing namespace throws NullPointerException")
    void missingNamespace_throws() {
      assertThrows(
          NullPointerException.class,
          () -> CaseDefinition.builder().name("test").version("1.0").build());
    }

    @Test
    @DisplayName("null name throws NullPointerException")
    void nullName_throws() {
      assertThrows(
          NullPointerException.class,
          () -> CaseDefinition.builder().namespace("ns").name(null).version("1.0").build());
    }

    @Test
    @DisplayName("null version throws NullPointerException")
    void nullVersion_throws() {
      assertThrows(
          NullPointerException.class,
          () -> CaseDefinition.builder().namespace("ns").name("test").version(null).build());
    }

    @Test
    @DisplayName("minimal valid build succeeds")
    void minimal_builds() {
      final var def =
          assertDoesNotThrow(
              () -> CaseDefinition.builder().namespace("ns").name("test").version("1.0").build());
      assertEquals("ns", def.getNamespace());
      assertEquals("test", def.getName());
      assertEquals("1.0", def.getVersion());
    }

    @Test
    @DisplayName("optional fields default to null/empty lists")
    void optionalFieldsDefaults() {
      final var def = CaseDefinition.builder().namespace("ns").name("test").version("1.0").build();
      assertNull(def.getTitle());
      assertNull(def.getSummary());
      assertNull(def.getCompletion());
      assertTrue(def.getCapabilities().isEmpty());
      assertTrue(def.getBindings().isEmpty());
      assertTrue(def.getMilestones().isEmpty());
      assertTrue(def.getGoals().isEmpty());
      assertTrue(def.getWorkers().isEmpty());
    }

    @Test
    @DisplayName("equals: two definitions with same namespace/name/version are equal")
    void equals_sameCoordinates_equal() {
      final var a = CaseDefinition.builder().namespace("ns").name("case").version("1.0").build();
      final var b = CaseDefinition.builder().namespace("ns").name("case").version("1.0").build();
      assertEquals(a, b);
      assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("equals: different version means not equal")
    void equals_differentVersion_notEqual() {
      final var a = CaseDefinition.builder().namespace("ns").name("case").version("1.0").build();
      final var b = CaseDefinition.builder().namespace("ns").name("case").version("2.0").build();
      assertNotEquals(a, b);
    }

    @Test
    @DisplayName("equals: different namespace means not equal")
    void equals_differentNamespace_notEqual() {
      final var a = CaseDefinition.builder().namespace("ns-a").name("case").version("1.0").build();
      final var b = CaseDefinition.builder().namespace("ns-b").name("case").version("1.0").build();
      assertNotEquals(a, b);
    }

    @Test
    @DisplayName("equals: not equal to null")
    void equals_null_notEqual() {
      final var a = CaseDefinition.builder().namespace("ns").name("case").version("1.0").build();
      assertNotEquals(a, null);
    }

    @Test
    @DisplayName(
        "completion(String when) creates PredicateBasedCompletion with JQExpressionEvaluator")
    void completionStringWhen_createsPredicateBased() {
      final var def =
          CaseDefinition.builder()
              .namespace("ns")
              .name("test")
              .version("1.0")
              .completion(".status == \"done\"")
              .build();
      assertInstanceOf(PredicateBasedCompletion.class, def.getCompletion());
      final var pbc = (PredicateBasedCompletion) def.getCompletion();
      assertInstanceOf(JQExpressionEvaluator.class, pbc.getDoneWhen());
      assertEquals(".status == \"done\"", ((JQExpressionEvaluator) pbc.getDoneWhen()).expression());
    }

    @Test
    @DisplayName("completion(GoalExpression) creates GoalBasedCompletion with null failure")
    void completionGoalExpression_createsGoalBased() {
      final var goal =
          Goal.builder().name("g").condition(".done == true").kind(GoalKind.SUCCESS).build();
      final var def =
          CaseDefinition.builder()
              .namespace("ns")
              .name("test")
              .version("1.0")
              .completion(GoalExpression.allOf(goal))
              .build();
      assertInstanceOf(GoalBasedCompletion.class, def.getCompletion());
      final var gbc = (GoalBasedCompletion) def.getCompletion();
      assertNotNull(gbc.getSuccess());
      assertNull(gbc.getFailure());
    }

    @Test
    @DisplayName("completion(success, failure) stores both expressions")
    void completionSuccessFailure_storesBoth() {
      final var successGoal =
          Goal.builder().name("g-success").condition(".ok == true").kind(GoalKind.SUCCESS).build();
      final var failGoal =
          Goal.builder().name("g-fail").condition(".error == true").kind(GoalKind.FAILURE).build();
      final var def =
          CaseDefinition.builder()
              .namespace("ns")
              .name("test")
              .version("1.0")
              .completion(GoalExpression.allOf(successGoal), GoalExpression.anyOf(failGoal))
              .build();
      assertInstanceOf(GoalBasedCompletion.class, def.getCompletion());
      final var gbc = (GoalBasedCompletion) def.getCompletion();
      assertNotNull(gbc.getSuccess());
      assertNotNull(gbc.getFailure());
    }
  }

  // ================================================================== //
  //  Binding                                                            //
  // ================================================================== //

  @Nested
  @DisplayName("Binding builder")
  class BindingBuilderTests {

    private final Capability cap =
        Capability.builder().name("cap1").inputSchema("{}").outputSchema("{}").build();
    private final ContextChangeTrigger trigger = new ContextChangeTrigger(".x == true");

    @Test
    @DisplayName("null name throws NullPointerException")
    void nullName_throws() {
      assertThrows(
          NullPointerException.class,
          () -> Binding.builder().name(null).capability(cap).on(trigger).build());
    }

    @Test
    @DisplayName("neither capability nor subCase throws IllegalStateException")
    void nullCapability_throws() {
      assertThrows(
          IllegalStateException.class, () -> Binding.builder().name("binding").on(trigger).build());
    }

    @Test
    @DisplayName("null trigger throws NullPointerException")
    void nullTrigger_throws() {
      assertThrows(
          NullPointerException.class,
          () -> Binding.builder().name("binding").capability(cap).on(null).build());
    }

    @Test
    @DisplayName("minimal valid build succeeds")
    void minimal_builds() {
      final var binding =
          assertDoesNotThrow(
              () -> Binding.builder().name("on-event").capability(cap).on(trigger).build());
      assertEquals("on-event", binding.getName());
      assertInstanceOf(io.casehub.api.model.CapabilityTarget.class, binding.target());
      assertEquals(cap, ((io.casehub.api.model.CapabilityTarget) binding.target()).capability());
      assertEquals(trigger, binding.getOn());
      assertNull(binding.getWhen(), "when is optional, should default to null");
    }

    @Test
    @DisplayName("when(String) creates JQExpressionEvaluator")
    void whenString_createsJQEvaluator() {
      final var binding =
          Binding.builder().name("b").capability(cap).on(trigger).when(".flag == true").build();
      assertInstanceOf(JQExpressionEvaluator.class, binding.getWhen());
      assertEquals(".flag == true", ((JQExpressionEvaluator) binding.getWhen()).expression());
    }
  }

  // ================================================================== //
  //  Milestone                                                          //
  // ================================================================== //

  @Nested
  @DisplayName("Milestone builder")
  class MilestoneBuilderTests {

    @Test
    @DisplayName("null name throws NullPointerException")
    void nullName_throws() {
      assertThrows(
          NullPointerException.class,
          () -> Milestone.builder().name(null).completionCriteria(".x == true").build());
    }

    @Test
    @DisplayName("null ExpressionEvaluator condition throws NullPointerException")
    void nullEvaluatorCondition_throws() {
      assertThrows(
          NullPointerException.class,
          () ->
              Milestone.builder().name("m").completionCriteria((ExpressionEvaluator) null).build());
    }

    @Test
    @DisplayName("null String condition throws NullPointerException")
    void nullStringCondition_throws() {
      assertThrows(
          NullPointerException.class,
          () -> Milestone.builder().name("m").completionCriteria((String) null).build());
    }

    @Test
    @DisplayName("condition(String) creates JQExpressionEvaluator")
    void conditionString_createsJQEvaluator() {
      final var m = Milestone.builder().name("m").completionCriteria(".done == true").build();
      assertInstanceOf(JQExpressionEvaluator.class, m.getCompletionCriteria());
      assertEquals(
          ".done == true", ((JQExpressionEvaluator) m.getCompletionCriteria()).expression());
    }

    @Test
    @DisplayName("equals: same name/condition/description are equal")
    void equals_sameFields_equal() {
      final var a =
          Milestone.builder()
              .name("m")
              .completionCriteria(".x == true")
              .description("desc")
              .build();
      final var b =
          Milestone.builder()
              .name("m")
              .completionCriteria(".x == true")
              .description("desc")
              .build();
      assertEquals(a, b);
      assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("equals: different name is not equal")
    void equals_differentName_notEqual() {
      final var a = Milestone.builder().name("m1").completionCriteria(".x == true").build();
      final var b = Milestone.builder().name("m2").completionCriteria(".x == true").build();
      assertNotEquals(a, b);
    }

    @Test
    @DisplayName("equals: different condition is not equal")
    void equals_differentCondition_notEqual() {
      final var a = Milestone.builder().name("m").completionCriteria(".x == true").build();
      final var b = Milestone.builder().name("m").completionCriteria(".y == true").build();
      assertNotEquals(a, b);
    }

    @Test
    @DisplayName("description is optional — null default")
    void descriptionDefaultsToNull() {
      final var m = Milestone.builder().name("m").completionCriteria(".x == true").build();
      assertNull(m.getDescription());
    }

    @Test
    @DisplayName("description setter works")
    void descriptionSetter() {
      final var m =
          Milestone.builder()
              .name("m")
              .completionCriteria(".x == true")
              .description("my milestone")
              .build();
      assertEquals("my milestone", m.getDescription());
    }

    @Test
    @DisplayName("condition(Predicate) creates LambdaExpressionEvaluator")
    void conditionPredicate_createsLambdaEvaluator() {
      final var m =
          Milestone.builder().name("m").completionCriteria((CaseContext ctx) -> true).build();
      assertInstanceOf(LambdaExpressionEvaluator.class, m.getCompletionCriteria());
    }

    @Test
    @DisplayName("null Predicate condition throws NullPointerException")
    void nullPredicateCondition_throws() {
      assertThrows(
          NullPointerException.class,
          () ->
              Milestone.builder()
                  .name("m")
                  .completionCriteria((Predicate<CaseContext>) null)
                  .build());
    }
  }

  // ================================================================== //
  //  Goal                                                               //
  // ================================================================== //

  @Nested
  @DisplayName("Goal builder")
  class GoalBuilderTests {

    @Test
    @DisplayName("null name throws NullPointerException")
    void nullName_throws() {
      assertThrows(
          NullPointerException.class,
          () -> Goal.builder().name(null).condition(".x == true").kind(GoalKind.SUCCESS).build());
    }

    @Test
    @DisplayName("null ExpressionEvaluator condition throws NullPointerException")
    void nullEvaluatorCondition_throws() {
      assertThrows(
          NullPointerException.class,
          () ->
              Goal.builder()
                  .name("g")
                  .condition((ExpressionEvaluator) null)
                  .kind(GoalKind.SUCCESS)
                  .build());
    }

    @Test
    @DisplayName("null String condition throws NullPointerException")
    void nullStringCondition_throws() {
      assertThrows(
          NullPointerException.class,
          () -> Goal.builder().name("g").condition((String) null).kind(GoalKind.SUCCESS).build());
    }

    @Test
    @DisplayName("null kind throws NullPointerException")
    void nullKind_throws() {
      assertThrows(
          NullPointerException.class,
          () -> Goal.builder().name("g").condition(".x == true").kind(null).build());
    }

    @Test
    @DisplayName("condition(String) creates JQExpressionEvaluator")
    void conditionString_createsJQEvaluator() {
      final var g =
          Goal.builder().name("g").condition(".done == true").kind(GoalKind.SUCCESS).build();
      assertInstanceOf(JQExpressionEvaluator.class, g.getCondition());
      assertEquals(".done == true", ((JQExpressionEvaluator) g.getCondition()).expression());
    }

    @Test
    @DisplayName("equals: same name/condition/kind/terminal/description are equal")
    void equals_sameFields_equal() {
      final var a =
          Goal.builder()
              .name("g")
              .condition(".x == true")
              .kind(GoalKind.SUCCESS)
              .terminal(false)
              .description("d")
              .build();
      final var b =
          Goal.builder()
              .name("g")
              .condition(".x == true")
              .kind(GoalKind.SUCCESS)
              .terminal(false)
              .description("d")
              .build();
      assertEquals(a, b);
      assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("equals: different kind is not equal")
    void equals_differentKind_notEqual() {
      final var a = Goal.builder().name("g").condition(".x == true").kind(GoalKind.SUCCESS).build();
      final var b = Goal.builder().name("g").condition(".x == true").kind(GoalKind.FAILURE).build();
      assertNotEquals(a, b);
    }

    @Test
    @DisplayName("terminal defaults to false")
    void terminalDefaultsFalse() {
      final var g = Goal.builder().name("g").condition(".x == true").kind(GoalKind.SUCCESS).build();
      assertFalse(g.getTerminal());
    }

    @Test
    @DisplayName("terminal(true) is stored correctly")
    void terminalTrue_stored() {
      final var g =
          Goal.builder()
              .name("g")
              .condition(".x == true")
              .kind(GoalKind.SUCCESS)
              .terminal(true)
              .build();
      assertTrue(g.getTerminal());
    }

    @Test
    @DisplayName("SUCCESS and FAILURE goals with same name are not equal")
    void successVsFailure_notEqual() {
      final var success =
          Goal.builder().name("g").condition(".x == true").kind(GoalKind.SUCCESS).build();
      final var failure =
          Goal.builder().name("g").condition(".x == true").kind(GoalKind.FAILURE).build();
      assertNotEquals(success, failure);
    }

    @Test
    @DisplayName("condition(Predicate) creates LambdaExpressionEvaluator")
    void conditionPredicate_createsLambdaEvaluator() {
      final var g =
          Goal.builder()
              .name("g")
              .condition((CaseContext ctx) -> true)
              .kind(GoalKind.SUCCESS)
              .build();
      assertInstanceOf(LambdaExpressionEvaluator.class, g.getCondition());
    }

    @Test
    @DisplayName("null Predicate condition throws NullPointerException")
    void nullPredicateCondition_throws() {
      assertThrows(
          NullPointerException.class,
          () ->
              Goal.builder()
                  .name("g")
                  .condition((Predicate<CaseContext>) null)
                  .kind(GoalKind.SUCCESS)
                  .build());
    }
  }

  // ================================================================== //
  //  GoalKind                                                           //
  // ================================================================== //

  @Nested
  @DisplayName("GoalKind.fromValue()")
  class GoalKindFromValueTests {

    @Test
    @DisplayName("'success' maps to GoalKind.SUCCESS")
    void successString_mapsToSuccess() {
      assertEquals(GoalKind.SUCCESS, GoalKind.fromValue("success"));
    }

    @Test
    @DisplayName("'failure' maps to GoalKind.FAILURE")
    void failureString_mapsToFailure() {
      assertEquals(GoalKind.FAILURE, GoalKind.fromValue("failure"));
    }

    @Test
    @DisplayName("unknown value throws IllegalArgumentException")
    void unknownValue_throws() {
      assertThrows(IllegalArgumentException.class, () -> GoalKind.fromValue("unknown"));
    }

    @Test
    @DisplayName("null value throws (NPE or IAE — either is acceptable)")
    void nullValue_throws() {
      assertThrows(Exception.class, () -> GoalKind.fromValue(null));
    }

    @Test
    @DisplayName("upper-case 'SUCCESS' is not recognised — values are lower-case only")
    void uppercaseSuccess_throws() {
      assertThrows(IllegalArgumentException.class, () -> GoalKind.fromValue("SUCCESS"));
    }

    @Test
    @DisplayName("GoalKind.value() returns the canonical lower-case string")
    void value_returnsLowercase() {
      assertEquals("success", GoalKind.SUCCESS.value());
      assertEquals("failure", GoalKind.FAILURE.value());
    }

    @Test
    @DisplayName("GoalKind.toString() matches value()")
    void toString_matchesValue() {
      assertEquals(GoalKind.SUCCESS.value(), GoalKind.SUCCESS.toString());
      assertEquals(GoalKind.FAILURE.value(), GoalKind.FAILURE.toString());
    }
  }

  // ================================================================== //
  //  Capability                                                         //
  // ================================================================== //

  @Nested
  @DisplayName("Capability builder")
  class CapabilityBuilderTests {

    @Test
    @DisplayName("null name throws NullPointerException")
    void nullName_throws() {
      assertThrows(
          NullPointerException.class,
          () -> Capability.builder().name(null).inputSchema("{}").outputSchema("{}").build());
    }

    @Test
    @DisplayName("null inputSchema throws NullPointerException")
    void nullInputSchema_throws() {
      assertThrows(
          NullPointerException.class,
          () -> Capability.builder().name("cap").inputSchema(null).outputSchema("{}").build());
    }

    @Test
    @DisplayName("null outputSchema throws NullPointerException")
    void nullOutputSchema_throws() {
      assertThrows(
          NullPointerException.class,
          () -> Capability.builder().name("cap").inputSchema("{}").outputSchema(null).build());
    }

    @Test
    @DisplayName("minimal build succeeds")
    void minimal_builds() {
      final var cap =
          assertDoesNotThrow(
              () ->
                  Capability.builder()
                      .name("cap")
                      .inputSchema("{input: .x}")
                      .outputSchema("{output: .y}")
                      .build());
      assertEquals("cap", cap.getName());
      assertEquals("{input: .x}", cap.getInputSchema());
      assertEquals("{output: .y}", cap.getOutputSchema());
      assertNull(cap.getDescription());
    }

    @Test
    @DisplayName("description setter works")
    void descriptionSetter() {
      final var cap =
          Capability.builder()
              .name("cap")
              .inputSchema("{}")
              .outputSchema("{}")
              .description("A capability")
              .build();
      assertEquals("A capability", cap.getDescription());
    }
  }

  // ================================================================== //
  //  ContextChangeTrigger                                               //
  // ================================================================== //

  @Nested
  @DisplayName("ContextChangeTrigger")
  class ContextChangeTriggerTests {

    @Test
    @DisplayName("String constructor wraps expression in JQExpressionEvaluator")
    void stringConstructor_wrapsInJQEvaluator() {
      final var trigger = new ContextChangeTrigger(".status == \"active\"");
      assertInstanceOf(JQExpressionEvaluator.class, trigger.getFilter());
      assertEquals(
          ".status == \"active\"", ((JQExpressionEvaluator) trigger.getFilter()).expression());
    }

    @Test
    @DisplayName("ExpressionEvaluator constructor stores the evaluator directly")
    void evaluatorConstructor_storesDirectly() {
      final var evaluator = new JQExpressionEvaluator(".x == 1");
      final var trigger = new ContextChangeTrigger(evaluator);
      assertEquals(evaluator, trigger.getFilter());
    }

    @Test
    @DisplayName("listenPanel is null when constructed without a panel name")
    void listenPanel_nullByDefault() {
      final var trigger = new ContextChangeTrigger(".result != null");
      assertNull(trigger.getListenPanel());
    }

    @Test
    @DisplayName("listenPanel is stored when constructed with evaluator + panel name")
    void listenPanel_storedWhenProvided() {
      final var evaluator = new JQExpressionEvaluator(".result != null");
      final var trigger = new ContextChangeTrigger(evaluator, "extracted");
      assertEquals("extracted", trigger.getListenPanel());
      assertEquals(evaluator, trigger.getFilter());
    }
  }

  // ================================================================== //
  //  CaseDefinition panel builder                                       //
  // ================================================================== //

  @Nested
  @DisplayName("CaseDefinition panel builder")
  class PanelBuilderTests {

    @Test
    @DisplayName("panel(String) accumulates panel names in order")
    void panel_accumulatesNames() {
      final var def =
          CaseDefinition.builder()
              .namespace("test")
              .name("n")
              .version("1.0")
              .panel("raw")
              .panel("extracted")
              .panel("conclusions")
              .build();
      assertEquals(java.util.List.of("raw", "extracted", "conclusions"), def.getPanelNames());
    }

    @Test
    @DisplayName("panels(String...) adds multiple names in one call")
    void panels_varargs_addsAll() {
      final var def =
          CaseDefinition.builder()
              .namespace("test")
              .name("n")
              .version("1.0")
              .panels("alpha", "beta", "gamma")
              .build();
      assertEquals(java.util.List.of("alpha", "beta", "gamma"), def.getPanelNames());
    }

    @Test
    @DisplayName("panel() and panels() can be mixed in the same builder chain")
    void panel_and_panels_mixed() {
      final var def =
          CaseDefinition.builder()
              .namespace("test")
              .name("n")
              .version("1.0")
              .panel("first")
              .panels("second", "third")
              .panel("fourth")
              .build();
      assertEquals(java.util.List.of("first", "second", "third", "fourth"), def.getPanelNames());
    }

    @Test
    @DisplayName("panelNames is null when no panel methods are called")
    void panelNames_nullByDefault() {
      final var def = CaseDefinition.builder().namespace("ns").name("test").version("1.0").build();
      assertNull(def.getPanelNames());
    }
  }

  // ================================================================== //
  //  PredicateBasedCompletion                                           //
  // ================================================================== //

  @Nested
  @DisplayName("PredicateBasedCompletion")
  class PredicateBasedCompletionTests {

    @Test
    @DisplayName("stores the provided evaluator")
    void storesEvaluator() {
      final var evaluator = new JQExpressionEvaluator(".done == true");
      final var completion = new PredicateBasedCompletion(evaluator);
      assertEquals(evaluator, completion.getDoneWhen());
    }

    @Test
    @DisplayName("null evaluator is stored (validation is caller's responsibility)")
    void nullEvaluator_stored() {
      final var completion = new PredicateBasedCompletion(null);
      assertNull(completion.getDoneWhen());
    }
  }
}
