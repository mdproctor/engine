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
package io.casehub.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests that the generated {@link CaseCompletion} model correctly deserializes completion entries
 * as typed {@link GoalExpression} instances with full {@code allOf}/{@code anyOf} structure.
 *
 * <h2>Background</h2>
 *
 * <p>Issue #582 generalized {@code GoalBasedCompletion} from fixed {@code success}/{@code failure}
 * properties to an open map supporting custom goal kinds (e.g. {@code escalated}, {@code referred},
 * {@code withdrawn}). The YAML schema changed from:
 *
 * <pre>{@code
 * CaseCompletion:
 *   properties:
 *     success:
 *       $ref: "#/$defs/GoalExpression"
 *     failure:
 *       $ref: "#/$defs/GoalExpression"
 *   unevaluatedProperties: false
 * }</pre>
 *
 * <p>to {@code additionalProperties: true}, which lost all type information. The generated {@code
 * CaseCompletion} class had only a {@code doneWhen} field — completion entries like {@code success}
 * and {@code failure} were silently discarded during deserialization, and consumers using
 * jsonschema2pojo had no way to access them.
 *
 * <h2>The fix (engine#699)</h2>
 *
 * <p>The schema now declares:
 *
 * <pre>{@code
 * CaseCompletion:
 *   properties:
 *     doneWhen:
 *       type: string
 *   additionalProperties:
 *     $ref: "#/$defs/GoalExpression"
 * }</pre>
 *
 * <p>This preserves the open-ended key names (any string can be a goal kind) while expressing that
 * every value conforms to {@link GoalExpression}. The generated class now has:
 *
 * <ul>
 *   <li>{@code Map<String, GoalExpression> getAdditionalProperties()} — typed map, no casting
 *   <li>{@code void setAdditionalProperty(String name, GoalExpression value)} — compile-time type
 *       checking on writes
 *   <li>{@code @JsonAnyGetter}/{@code @JsonAnySetter} — Jackson routes unknown properties through
 *       the typed map automatically
 *   <li>{@code LinkedHashMap} backing — preserves YAML document order, which determines evaluation
 *       priority (first satisfied expression wins)
 * </ul>
 *
 * <h2>How these tests are organized</h2>
 *
 * <table>
 *   <tr><th>Category</th><th>What it proves</th></tr>
 *   <tr><td>{@link TypeSafety}</td>
 *       <td>Map values are {@code GoalExpression}, not {@code Object} — the whole point of
 *       the fix. Consumers can call {@code .getAllOf()} and {@code .getAnyOf()} directly
 *       without casting.</td></tr>
 *   <tr><td>{@link StandardPatterns}</td>
 *       <td>The original {@code success}/{@code failure} YAML format still works identically
 *       after the schema change — full backward compatibility.</td></tr>
 *   <tr><td>{@link CustomGoalKinds}</td>
 *       <td>Domains can define their own terminal outcomes beyond success/failure. This is
 *       why the schema was generalized in the first place (#582) — AML needs
 *       {@code escalated}, clinical needs {@code referred}, legal needs
 *       {@code withdrawn}/{@code settled}.</td></tr>
 *   <tr><td>{@link EvaluationOrder}</td>
 *       <td>YAML document order is preserved in the map. The engine evaluates completion
 *       entries in insertion order and the first satisfied expression wins — so ordering
 *       matters (e.g. failure-before-success means failure is checked first).</td></tr>
 *   <tr><td>{@link GoalExpressionVariations}</td>
 *       <td>{@code allOf} (conjunction — all goals must be satisfied) and {@code anyOf}
 *       (disjunction — any one goal triggers the outcome) work correctly across all goal
 *       kinds, with single or multiple goals.</td></tr>
 *   <tr><td>{@link DoneWhen}</td>
 *       <td>{@code doneWhen} is a named property (JQ predicate shortcut), not a goal
 *       expression entry — it stays on {@code getDoneWhen()}, not in the map.</td></tr>
 *   <tr><td>{@link EdgeCases}</td>
 *       <td>Empty blocks, missing blocks, and single-kind completions all parse
 *       correctly.</td></tr>
 *   <tr><td>{@link RoundTrip}</td>
 *       <td>Serialize → deserialize preserves the full structure including goal kind names,
 *       expression types, and goal references. Also proves programmatic construction
 *       round-trips correctly.</td></tr>
 * </table>
 *
 * @see CaseCompletion
 * @see GoalExpression
 * @see io.casehub.codegen.CasehubRuleFactory
 */
@DisplayName("CaseCompletion schema — typed additionalProperties")
class CaseCompletionDeserializationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

  /**
   * Parses a YAML case definition and returns just the completion block. Convenience method so
   * individual tests focus on the completion structure without repeating boilerplate.
   */
  private static CaseCompletion parseCompletion(String yaml) throws IOException {
    CaseDefinition def = MAPPER.readValue(yaml, CaseDefinition.class);
    return def.getSpec().getCompletion();
  }

  // ── Type safety ────────────────────────────────────────────────────────────

  /**
   * Proves that completion entries are deserialized as {@link GoalExpression} instances, not raw
   * {@code Object}. This is the core value of the schema fix — without the typed {@code $ref},
   * consumers would get {@code Map<String, Object>} and need unsafe casts to access {@code
   * allOf}/{@code anyOf}.
   */
  @Nested
  @DisplayName("Type safety — completion entries are GoalExpression, not Object")
  class TypeSafety {

    /** Verifies deserialized map values are GoalExpression — no casting required. */
    @Test
    @DisplayName("map values are GoalExpression instances — no casting required")
    void mapValuesAreGoalExpression() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: test
              name: Type Safety
              version: 1.0.0
              spec:
                completion:
                  success:
                    allOf: [goal-a]
              """);

      Map<String, GoalExpression> entries = completion.getAdditionalProperties();

      GoalExpression success = entries.get("success");
      assertInstanceOf(GoalExpression.class, success);
      assertEquals(List.of("goal-a"), success.getAllOf());
    }

    /**
     * Verifies that the setter enforces type safety at compile time. Before the fix, {@code
     * setAdditionalProperty} accepted {@code Object} — any value could be stored. Now it only
     * accepts {@code GoalExpression}, catching type errors during compilation.
     */
    @Test
    @DisplayName("setAdditionalProperty accepts GoalExpression — compile-time type checking")
    void programmaticConstructionIsTyped() {
      CaseCompletion completion = new CaseCompletion();

      GoalExpression expr = new GoalExpression();
      expr.setAllOf(List.of("goal-x", "goal-y"));

      completion.setAdditionalProperty("success", expr);

      GoalExpression retrieved = completion.getAdditionalProperties().get("success");
      assertEquals(List.of("goal-x", "goal-y"), retrieved.getAllOf());
    }
  }

  // ── Standard completion patterns ───────────────────────────────────────────

  /**
   * Proves backward compatibility — the original {@code success}/{@code failure} YAML format that
   * existed before issue #582 still works identically. Consumers using the standard two-kind
   * pattern do not need to change anything.
   */
  @Nested
  @DisplayName("Standard success/failure — backward compatible")
  class StandardPatterns {

    /** {@code allOf} requires every listed goal to be satisfied before success is declared. */
    @Test
    @DisplayName("success with allOf — all goals must be satisfied")
    void successAllOf() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: test
              name: AllOf
              version: 1.0.0
              spec:
                completion:
                  success:
                    allOf: [verified, approved, documented]
              """);

      GoalExpression success = completion.getAdditionalProperties().get("success");
      assertEquals(List.of("verified", "approved", "documented"), success.getAllOf());
      assertTrue(success.getAnyOf().isEmpty());
    }

    /** {@code anyOf} triggers the outcome as soon as any single listed goal is satisfied. */
    @Test
    @DisplayName("failure with anyOf — any single goal triggers failure")
    void failureAnyOf() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: test
              name: AnyOf
              version: 1.0.0
              spec:
                completion:
                  failure:
                    anyOf: [fraud-detected, sanctions-hit, timeout-expired]
              """);

      GoalExpression failure = completion.getAdditionalProperties().get("failure");
      assertEquals(
          List.of("fraud-detected", "sanctions-hit", "timeout-expired"), failure.getAnyOf());
      assertTrue(failure.getAllOf().isEmpty());
    }

    /**
     * Standard pattern: failure (anyOf) checked before success (allOf). Both entries coexist in the
     * map with evaluation order preserved from the YAML source.
     */
    @Test
    @DisplayName("success and failure together — evaluation order preserved")
    void successAndFailure() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: test
              name: Both
              version: 1.0.0
              spec:
                completion:
                  failure:
                    anyOf: [sla-breached]
                  success:
                    allOf: [pr-approved, tests-passed]
              """);

      Map<String, GoalExpression> entries = completion.getAdditionalProperties();
      assertEquals(2, entries.size());

      assertEquals(List.of("sla-breached"), entries.get("failure").getAnyOf());
      assertEquals(List.of("pr-approved", "tests-passed"), entries.get("success").getAllOf());
    }
  }

  // ── Custom goal kinds ──────────────────────────────────────────────────────

  /**
   * Demonstrates the primary motivation for the #582 generalization: domains need terminal outcomes
   * beyond success/failure. Each test models a real domain scenario where the hardcoded two-kind
   * model was insufficient.
   *
   * <p>Custom kinds map to engine-level terminal statuses via {@code GoalKind.terminalStatus()}:
   *
   * <ul>
   *   <li>{@code success} → {@code CaseStatus.COMPLETED} (built-in)
   *   <li>{@code failure} → {@code CaseStatus.FAULTED} (built-in)
   *   <li>{@code escalated} → {@code CaseStatus.FAULTED} (custom, requires explicit status in YAML)
   *   <li>{@code referred} → {@code CaseStatus.COMPLETED} (custom)
   *   <li>{@code withdrawn} → {@code CaseStatus.COMPLETED} (custom)
   * </ul>
   *
   * <p>The schema and generated model are agnostic to the terminal status — that mapping is handled
   * by the engine's {@code CaseDefinitionYamlMapper} and {@code GoalReachedEventHandler}. The
   * generated model's job is to faithfully represent the YAML structure with type safety.
   */
  @Nested
  @DisplayName("Custom goal kinds — domain-specific terminal outcomes")
  class CustomGoalKinds {

    /**
     * AML (Anti-Money Laundering): transactions can be escalated for manual review — a distinct
     * outcome from outright failure (sanctions match) or success (clean assessment).
     */
    @Test
    @DisplayName("AML domain: escalated outcome alongside success/failure")
    void amlEscalatedOutcome() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: aml
              name: Transaction Review
              version: 1.0.0
              spec:
                completion:
                  failure:
                    anyOf: [sanctions-match]
                  escalated:
                    anyOf: [manual-review-required]
                  success:
                    allOf: [risk-assessed, decision-recorded]
              """);

      Map<String, GoalExpression> entries = completion.getAdditionalProperties();
      assertEquals(3, entries.size());

      assertEquals(List.of("sanctions-match"), entries.get("failure").getAnyOf());
      assertEquals(List.of("manual-review-required"), entries.get("escalated").getAnyOf());
      assertEquals(
          List.of("risk-assessed", "decision-recorded"), entries.get("success").getAllOf());
    }

    /**
     * Clinical triage: a patient may need specialist referral — neither a failure nor a successful
     * treatment within the current scope.
     */
    @Test
    @DisplayName("clinical domain: referred outcome for specialist handoff")
    void clinicalReferredOutcome() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: clinical
              name: Triage Assessment
              version: 1.0.0
              spec:
                completion:
                  referred:
                    anyOf: [specialist-needed, out-of-scope]
                  success:
                    allOf: [assessment-complete, treatment-plan-created]
              """);

      Map<String, GoalExpression> entries = completion.getAdditionalProperties();
      assertEquals(2, entries.size());

      GoalExpression referred = entries.get("referred");
      assertEquals(List.of("specialist-needed", "out-of-scope"), referred.getAnyOf());
    }

    /**
     * Legal dispute: cases can be withdrawn by the applicant or settled by agreement — both are
     * valid terminal states distinct from failure (statute expired) or a court-decided success.
     */
    @Test
    @DisplayName("legal domain: withdrawn outcome for voluntary case closure")
    void legalWithdrawnOutcome() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: legal
              name: Dispute Resolution
              version: 1.0.0
              spec:
                completion:
                  withdrawn:
                    anyOf: [applicant-withdrew]
                  settled:
                    allOf: [terms-agreed, payment-confirmed]
                  failure:
                    anyOf: [statute-expired]
              """);

      Map<String, GoalExpression> entries = completion.getAdditionalProperties();
      assertEquals(3, entries.size());

      assertEquals(List.of("applicant-withdrew"), entries.get("withdrawn").getAnyOf());
      assertEquals(List.of("terms-agreed", "payment-confirmed"), entries.get("settled").getAllOf());
      assertEquals(List.of("statute-expired"), entries.get("failure").getAnyOf());
    }
  }

  // ── Evaluation order ───────────────────────────────────────────────────────

  /**
   * The engine evaluates completion entries in insertion order — the first satisfied expression
   * wins. YAML document order must therefore be preserved during deserialization. The backing
   * {@link java.util.LinkedHashMap} guarantees this; these tests verify the contract holds
   * end-to-end from YAML source through to the generated model.
   *
   * <p>Order matters in practice: if both a failure goal and a success goal are satisfied on the
   * same context change, the YAML author controls which takes precedence by listing it first.
   */
  @Nested
  @DisplayName("Evaluation order — YAML document order is preserved")
  class EvaluationOrder {

    /** Failure listed before success — failure is evaluated first. */
    @Test
    @DisplayName("failure-first ordering: failure checked before success")
    void failureFirstOrdering() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: test
              name: Failure First
              version: 1.0.0
              spec:
                completion:
                  failure:
                    anyOf: [fraud-detected]
                  success:
                    allOf: [approved]
              """);

      List<String> keys = List.copyOf(completion.getAdditionalProperties().keySet());
      assertEquals(List.of("failure", "success"), keys);
    }

    /** Five custom kinds — verifies order preservation at scale, not just two entries. */
    @Test
    @DisplayName("five custom kinds: document order preserved exactly")
    void fiveKindsOrderPreserved() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: test
              name: Five Kinds
              version: 1.0.0
              spec:
                completion:
                  critical-failure:
                    anyOf: [data-breach]
                  escalated:
                    anyOf: [needs-supervisor]
                  referred:
                    anyOf: [out-of-jurisdiction]
                  failure:
                    anyOf: [timeout]
                  success:
                    allOf: [resolved]
              """);

      List<String> keys = List.copyOf(completion.getAdditionalProperties().keySet());
      assertEquals(
          List.of("critical-failure", "escalated", "referred", "failure", "success"), keys);
    }
  }

  // ── allOf / anyOf variations ───────────────────────────────────────────────

  /**
   * {@link GoalExpression} supports two composition modes:
   *
   * <ul>
   *   <li>{@code allOf} — conjunction: every listed goal must be satisfied (AND semantics)
   *   <li>{@code anyOf} — disjunction: any single listed goal triggers the outcome (OR semantics)
   * </ul>
   *
   * <p>These tests verify that both modes work correctly with varying numbers of goals and that
   * different kinds can use different modes in the same completion block.
   */
  @Nested
  @DisplayName("allOf and anyOf — conjunction and disjunction over goals")
  class GoalExpressionVariations {

    /** Minimal allOf — single goal reference. */
    @Test
    @DisplayName("allOf with single goal — simplest conjunction")
    void allOfSingleGoal() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: test
              name: Single AllOf
              version: 1.0.0
              spec:
                completion:
                  success:
                    allOf: [done]
              """);

      GoalExpression success = completion.getAdditionalProperties().get("success");
      assertEquals(List.of("done"), success.getAllOf());
    }

    /** Multi-step pipeline: all five goals must complete for success. */
    @Test
    @DisplayName("allOf with many goals — all must be satisfied")
    void allOfManyGoals() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: test
              name: Many AllOf
              version: 1.0.0
              spec:
                completion:
                  success:
                    allOf: [data-collected, validated, enriched, scored, decision-made]
              """);

      GoalExpression success = completion.getAdditionalProperties().get("success");
      assertEquals(
          List.of("data-collected", "validated", "enriched", "scored", "decision-made"),
          success.getAllOf());
    }

    /** Minimal anyOf — single goal reference. */
    @Test
    @DisplayName("anyOf with single goal — simplest disjunction")
    void anyOfSingleGoal() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: test
              name: Single AnyOf
              version: 1.0.0
              spec:
                completion:
                  failure:
                    anyOf: [cancelled]
              """);

      GoalExpression failure = completion.getAdditionalProperties().get("failure");
      assertEquals(List.of("cancelled"), failure.getAnyOf());
    }

    /**
     * Different kinds using different expression modes in the same completion block: failure uses
     * anyOf (any trigger is enough), escalated and success use allOf (multiple conditions must
     * hold). Verifies that allOf and anyOf are independent per-entry and that unused fields remain
     * empty (not null).
     */
    @Test
    @DisplayName("mixed allOf and anyOf across different kinds")
    void mixedExpressionsAcrossKinds() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: test
              name: Mixed Expressions
              version: 1.0.0
              spec:
                completion:
                  failure:
                    anyOf: [fraud, sanctions, timeout]
                  escalated:
                    allOf: [flagged, supervisor-notified]
                  success:
                    allOf: [verified, approved]
              """);

      Map<String, GoalExpression> entries = completion.getAdditionalProperties();

      assertEquals(List.of("fraud", "sanctions", "timeout"), entries.get("failure").getAnyOf());
      assertTrue(entries.get("failure").getAllOf().isEmpty());

      assertEquals(List.of("flagged", "supervisor-notified"), entries.get("escalated").getAllOf());
      assertTrue(entries.get("escalated").getAnyOf().isEmpty());

      assertEquals(List.of("verified", "approved"), entries.get("success").getAllOf());
      assertTrue(entries.get("success").getAnyOf().isEmpty());
    }

    @Test
    @DisplayName("nested composition — allOf with nested anyOf subexpression")
    void nestedComposition() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
                          namespace: test
                          name: Nested
                          version: 1.0.0
                          spec:
                            completion:
                              success:
                                allOf:
                                  - data-collected
                                  - anyOf:
                                      - manual-review-passed
                                      - auto-approved
                          """);

      GoalExpression success = completion.getAdditionalProperties().get("success");
      List<Object> allOfItems = success.getAllOf();
      assertEquals(2, allOfItems.size());
      assertEquals("data-collected", allOfItems.get(0));
      assertInstanceOf(Map.class, allOfItems.get(1));
      @SuppressWarnings("unchecked")
      Map<String, Object> nestedExpr = (Map<String, Object>) allOfItems.get(1);
      assertEquals(List.of("manual-review-passed", "auto-approved"), nestedExpr.get("anyOf"));
    }
  }

  // ── doneWhen shortcut ──────────────────────────────────────────────────────

  /**
   * {@code doneWhen} is a named property on {@link CaseCompletion} — a JQ predicate shortcut for
   * simple completion conditions. It is NOT a goal expression entry and does not appear in the
   * additional properties map. The engine enforces mutual exclusion between {@code doneWhen} and
   * goal kind entries at parse time (see {@code
   * CaseDefinitionYamlMapperTest.completion_doneWhenWithGoalEntries_throwsMutualExclusion}).
   */
  @Nested
  @DisplayName("doneWhen — JQ predicate shortcut (named property, not a goal kind)")
  class DoneWhen {

    /** doneWhen populates getDoneWhen(), not the additional properties map. */
    @Test
    @DisplayName("doneWhen is a named property, not a goal expression entry")
    void doneWhenIsNamedProperty() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: test
              name: DoneWhen
              version: 1.0.0
              spec:
                completion:
                  doneWhen: '.allDone == true'
              """);

      assertEquals(".allDone == true", completion.getDoneWhen());
      assertTrue(completion.getAdditionalProperties().isEmpty());
    }
  }

  // ── Edge cases ─────────────────────────────────────────────────────────────

  /** Boundary conditions for the completion block. */
  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {

    /** Empty object — valid YAML, produces an empty completion with no entries. */
    @Test
    @DisplayName("empty completion block — no entries, no doneWhen")
    void emptyCompletion() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: test
              name: Empty
              version: 1.0.0
              spec:
                completion: {}
              """);

      assertNotNull(completion);
      assertNull(completion.getDoneWhen());
      assertTrue(completion.getAdditionalProperties().isEmpty());
    }

    /** No completion key in spec at all — the completion object is null. */
    @Test
    @DisplayName("no completion block at all — spec.completion is null")
    void noCompletionBlock() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: test
              name: No Completion
              version: 1.0.0
              spec: {}
              """);

      assertNull(completion);
    }

    /** Single kind — the minimal valid goal-based completion. */
    @Test
    @DisplayName("single kind only — minimal valid completion")
    void singleKindOnly() throws IOException {
      CaseCompletion completion =
          parseCompletion(
              """
              namespace: test
              name: Single
              version: 1.0.0
              spec:
                completion:
                  success:
                    allOf: [done]
              """);

      assertEquals(1, completion.getAdditionalProperties().size());
      assertNotNull(completion.getAdditionalProperties().get("success"));
    }
  }

  // ── Round-trip serialization ───────────────────────────────────────────────

  /**
   * Verifies that the typed map survives serialization and deserialization without losing
   * structure. This matters because the generated model uses {@code @JsonAnyGetter} (which flattens
   * map entries into the parent JSON object) and {@code @JsonAnySetter} (which routes unknown
   * properties back into the typed map). If either annotation is missing or the type parameter is
   * wrong, round-tripping breaks silently.
   */
  @Nested
  @DisplayName("Round-trip — serialize and deserialize preserves structure")
  class RoundTrip {

    /**
     * YAML → CaseDefinition → YAML string → CaseDefinition: all goal kinds, expression types, and
     * goal references survive the full round trip.
     */
    @Test
    @DisplayName("completion with multiple kinds survives serialize/deserialize")
    void roundTripPreservesStructure() throws IOException {
      String yaml =
          """
          namespace: test
          name: RoundTrip
          version: 1.0.0
          spec:
            completion:
              failure:
                anyOf: [breach-detected]
              escalated:
                anyOf: [needs-review]
              success:
                allOf: [assessed, documented]
          """;

      CaseDefinition original = MAPPER.readValue(yaml, CaseDefinition.class);
      String serialized = MAPPER.writeValueAsString(original);
      CaseDefinition restored = MAPPER.readValue(serialized, CaseDefinition.class);

      CaseCompletion restoredCompletion = restored.getSpec().getCompletion();
      Map<String, GoalExpression> entries = restoredCompletion.getAdditionalProperties();
      assertEquals(3, entries.size());

      assertEquals(List.of("breach-detected"), entries.get("failure").getAnyOf());
      assertEquals(List.of("needs-review"), entries.get("escalated").getAnyOf());
      assertEquals(List.of("assessed", "documented"), entries.get("success").getAllOf());
    }

    /**
     * Programmatically built CaseCompletion → JSON → CaseCompletion: proves the generated setter
     * and getter work with Jackson's JSON mapper (not just YAML), and that programmatic
     * construction produces the same structure as deserialization.
     */
    @Test
    @DisplayName("programmatically built completion round-trips correctly")
    void programmaticRoundTrip() throws IOException {
      CaseCompletion completion = new CaseCompletion();

      GoalExpression success = new GoalExpression();
      success.setAllOf(List.of("goal-a", "goal-b"));
      completion.setAdditionalProperty("success", success);

      GoalExpression failure = new GoalExpression();
      failure.setAnyOf(List.of("goal-c"));
      completion.setAdditionalProperty("failure", failure);

      String json = new ObjectMapper().writeValueAsString(completion);
      CaseCompletion restored = new ObjectMapper().readValue(json, CaseCompletion.class);

      assertEquals(
          List.of("goal-a", "goal-b"),
          restored.getAdditionalProperties().get("success").getAllOf());
      assertEquals(List.of("goal-c"), restored.getAdditionalProperties().get("failure").getAnyOf());
    }
  }
}
