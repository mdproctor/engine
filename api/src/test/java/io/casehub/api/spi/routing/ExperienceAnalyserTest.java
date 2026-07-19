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
package io.casehub.api.spi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExperienceAnalyserTest {

  @Test
  void emptyExperiences_returnsEmptyMap() {
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).isEmpty();
  }

  @Test
  void noMatchingCapability_returnsEmptyMap() {
    var exp = experience(0.8, step("agent-a", "style-review", "SUCCESS"));
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).isEmpty();
  }

  @Test
  void noMatchingWorker_returnsEmptyMap() {
    var exp = experience(0.8, step("agent-b", "security-review", "SUCCESS"));
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).isEmpty();
  }

  @Test
  void singleSuccessStep_returnsFullScore() {
    var exp = experience(0.9, step("agent-a", "security-review", "SUCCESS"));
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).containsEntry("agent-a", 1.0);
  }

  @Test
  void singleFailureStep_returnsZeroScore() {
    var exp = experience(0.9, step("agent-a", "security-review", "FAILURE"));
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).containsEntry("agent-a", 0.0);
  }

  @Test
  void multipleExperiences_weightedAverage() {
    var exp1 = experience(0.9, step("agent-a", "security-review", "SUCCESS"));
    var exp2 = experience(0.3, step("agent-a", "security-review", "FAILURE"));
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp1, exp2),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result.get("agent-a")).isCloseTo(0.75, within(0.001));
  }

  @Test
  void unknownOutcome_skipped() {
    var exp = experience(0.8, step("agent-a", "security-review", "UNKNOWN_STATUS"));
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).isEmpty();
  }

  @Test
  void zeroSimilarity_experienceSkipped() {
    var exp = experience(0.0, step("agent-a", "security-review", "SUCCESS"));
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).isEmpty();
  }

  @Test
  void negativeSimilarity_experienceSkipped() {
    var exp = experience(-0.5, step("agent-a", "security-review", "SUCCESS"));
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).isEmpty();
  }

  @Test
  void multipleWorkers_independentScores() {
    var exp =
        experience(
            0.8,
            step("agent-a", "security-review", "SUCCESS"),
            step("agent-b", "security-review", "FAILURE"));
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a", "agent-b"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).containsEntry("agent-a", 1.0);
    assertThat(result).containsEntry("agent-b", 0.0);
  }

  @Test
  void customOutcomeWeights_appliedCorrectly() {
    var customWeights =
        Map.of(
            RoutingOutcome.SUCCESS, 0.5,
            RoutingOutcome.FAILURE, 0.0,
            RoutingOutcome.GATE_REJECTED, 0.0,
            RoutingOutcome.GATE_EXPIRED, 0.0);
    var exp = experience(1.0, step("agent-a", "security-review", "SUCCESS"));
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp), Set.of("agent-a"), "security-review", customWeights);
    assertThat(result).containsEntry("agent-a", 0.5);
  }

  @Test
  void gateExpired_partialWeight() {
    var exp = experience(1.0, step("agent-a", "security-review", "GATE_EXPIRED"));
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).containsEntry("agent-a", 0.5);
  }

  @Test
  void gateRejected_weakWeight() {
    var exp = experience(1.0, step("agent-a", "security-review", "GATE_REJECTED"));
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).containsEntry("agent-a", 0.25);
  }

  @Test
  void nullWorkerName_stepSkipped() {
    var nullWorkerStep =
        new ExperiencePlanStep("binding", "security-review", null, "SUCCESS", 0, Map.of());
    var exp = experience(0.8, nullWorkerStep);
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).isEmpty();
  }

  private static RetrievedExperience experience(double similarity, ExperiencePlanStep... steps) {
    return new RetrievedExperience(
        "problem", "solution", "COMPLETED", 1.0, similarity, Map.of(), List.of(steps), Map.of());
  }

  @Test
  void addedSteps_excludedFromStatistics() {
    var addedStep =
        new ExperiencePlanStep(
            "binding-agent-a",
            "security-review",
            "agent-a",
            "SUCCESS",
            0,
            Map.of(),
            "ADDED",
            "adapter recommendation");
    var retainedStep =
        new ExperiencePlanStep(
            "binding-agent-b",
            "security-review",
            "agent-b",
            "SUCCESS",
            0,
            Map.of(),
            "RETAINED",
            null);
    var exp =
        new RetrievedExperience(
            "problem",
            "solution",
            "COMPLETED",
            1.0,
            0.8,
            Map.of(),
            List.of(addedStep, retainedStep),
            Map.of());
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a", "agent-b"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).doesNotContainKey("agent-a");
    assertThat(result).containsEntry("agent-b", 1.0);
  }

  @Test
  void nonAddedAdaptationSteps_includedInStatistics() {
    var boostedStep =
        new ExperiencePlanStep(
            "binding-agent-a",
            "security-review",
            "agent-a",
            "SUCCESS",
            0,
            Map.of(),
            "BOOSTED",
            "high relevance");
    var exp =
        new RetrievedExperience(
            "problem", "solution", "COMPLETED", 1.0, 0.8, Map.of(), List.of(boostedStep), Map.of());
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).containsEntry("agent-a", 1.0);
  }

  @Test
  void nullAdaptationAction_includedInStatistics() {
    var unadaptedStep = step("agent-a", "security-review", "SUCCESS");
    var exp = experience(0.8, unadaptedStep);
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).containsEntry("agent-a", 1.0);
  }

  @Test
  void substitutedSteps_excludedFromStatistics() {
    var substitutedStep =
        new ExperiencePlanStep(
            "binding-agent-a",
            "security-review",
            "agent-a",
            "SUCCESS",
            0,
            Map.of(),
            "SUBSTITUTED",
            "replaced original-worker due to availability");
    var retainedStep =
        new ExperiencePlanStep(
            "binding-agent-b",
            "security-review",
            "agent-b",
            "SUCCESS",
            0,
            Map.of(),
            "RETAINED",
            null);
    var exp =
        new RetrievedExperience(
            "problem",
            "solution",
            "COMPLETED",
            1.0,
            0.8,
            Map.of(),
            List.of(substitutedStep, retainedStep),
            Map.of());
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a", "agent-b"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).doesNotContainKey("agent-a");
    assertThat(result).containsEntry("agent-b", 1.0);
  }

  @Test
  void substitutedAndAdded_bothExcluded() {
    var substitutedStep =
        new ExperiencePlanStep(
            "binding-agent-a",
            "security-review",
            "agent-a",
            "SUCCESS",
            0,
            Map.of(),
            "SUBSTITUTED",
            "replaced original-worker");
    var addedStep =
        new ExperiencePlanStep(
            "binding-agent-b",
            "security-review",
            "agent-b",
            "SUCCESS",
            0,
            Map.of(),
            "ADDED",
            "adapter recommendation");
    var boostedStep =
        new ExperiencePlanStep(
            "binding-agent-c",
            "security-review",
            "agent-c",
            "FAILURE",
            0,
            Map.of(),
            "BOOSTED",
            "high relevance");
    var exp =
        new RetrievedExperience(
            "problem",
            "solution",
            "COMPLETED",
            1.0,
            0.8,
            Map.of(),
            List.of(substitutedStep, addedStep, boostedStep),
            Map.of());
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a", "agent-b", "agent-c"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).doesNotContainKey("agent-a");
    assertThat(result).doesNotContainKey("agent-b");
    assertThat(result).containsEntry("agent-c", 0.0);
  }

  @Test
  void predicateOverload_matchesByBindingName() {
    var step = new ExperiencePlanStep("review-binding", null, "reviewer-a", "SUCCESS", 0, Map.of());
    var exp =
        new RetrievedExperience(
            "problem", "solution", "COMPLETED", 1.0, 0.8, Map.of(), List.of(step), Map.of());
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("reviewer-a"),
            s -> "review-binding".equals(s.bindingName()),
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).containsEntry("reviewer-a", 1.0);
  }

  @Test
  void predicateOverload_nullCapabilityName_noMatchOnCapabilityString() {
    var step = new ExperiencePlanStep("review-binding", null, "reviewer-a", "SUCCESS", 0, Map.of());
    var exp =
        new RetrievedExperience(
            "problem", "solution", "COMPLETED", 1.0, 0.8, Map.of(), List.of(step), Map.of());
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("reviewer-a"),
            "review-binding",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).isEmpty();
  }

  private static ExperiencePlanStep step(String worker, String capability, String outcome) {
    return new ExperiencePlanStep("binding-" + worker, capability, worker, outcome, 0, Map.of());
  }
}
