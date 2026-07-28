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
package io.casehub.engine.internal.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.node.NullNode;
import io.casehub.api.spi.routing.ExperiencePlanStep;
import io.casehub.api.spi.routing.HumanTaskCandidates;
import io.casehub.api.spi.routing.HumanTaskRoutingContext;
import io.casehub.api.spi.routing.HumanTaskRoutingResult;
import io.casehub.api.spi.routing.RetrievedExperience;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CbrHumanTaskRoutingStrategyTest {

  private final CbrHumanTaskRoutingStrategy strategy = new CbrHumanTaskRoutingStrategy();

  private HumanTaskRoutingContext context(
      String bindingName, List<RetrievedExperience> experiences) {
    return new HumanTaskRoutingContext(
        UUID.randomUUID(), bindingName, "test-tenant", NullNode.instance, experiences);
  }

  private HumanTaskCandidates candidates(Set<String> groups, Set<String> users) {
    return new HumanTaskCandidates(groups, users);
  }

  private RetrievedExperience experience(double similarity, ExperiencePlanStep... steps) {
    return new RetrievedExperience(
        "problem", "solution", "COMPLETED", 1.0, similarity, Map.of(), List.of(steps), Map.of());
  }

  private ExperiencePlanStep step(String bindingName, String workerName, String outcome) {
    return new ExperiencePlanStep(bindingName, null, workerName, outcome, 0, Map.of());
  }

  @Test
  void idIsCbr() {
    assertThat(strategy.id()).isEqualTo("cbr");
  }

  @Test
  void emptyExperiencesReturnsUnchanged() {
    var result =
        strategy.select(
            context("review-task", List.of()),
            candidates(Set.of("managers"), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Unchanged.class);
  }

  @Test
  void emptyUsersReturnsUnchanged() {
    var exp = experience(0.9, step("review-task", "alice", "SUCCESS"));
    var result =
        strategy.select(
            context("review-task", List.of(exp)), candidates(Set.of("managers"), Set.of()));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Unchanged.class);
  }

  @Test
  void scoresUsersByBindingName() {
    var exp = experience(0.9, step("review-task", "alice", "SUCCESS"));
    var result =
        strategy.select(
            context("review-task", List.of(exp)),
            candidates(Set.of("managers"), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateScores()).containsEntry("alice", 1.0);
  }

  @Test
  void enrichesUsersWithSuccessRateScores() {
    var exp1 = experience(0.9, step("review-task", "alice", "SUCCESS"));
    var exp2 =
        experience(
            0.9, step("review-task", "alice", "FAILURE"), step("review-task", "bob", "SUCCESS"));
    var result =
        strategy.select(
            context("review-task", List.of(exp1, exp2)),
            candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateScores()).containsKey("alice");
    assertThat(enriched.candidateScores()).containsKey("bob");
    assertThat(enriched.candidateScores().get("bob"))
        .isGreaterThan(enriched.candidateScores().get("alice"));
  }

  @Test
  void ignoresUsersNotInCandidateSet() {
    var exp = experience(0.9, step("review-task", "charlie", "SUCCESS"));
    var result =
        strategy.select(
            context("review-task", List.of(exp)), candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Unchanged.class);
  }

  @Test
  void ignoresStepsWithDifferentBindingName() {
    var exp = experience(0.9, step("other-task", "alice", "SUCCESS"));
    var result =
        strategy.select(
            context("review-task", List.of(exp)), candidates(Set.of(), Set.of("alice")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Unchanged.class);
  }

  @Test
  void addedStepsExcluded() {
    var addedStep =
        new ExperiencePlanStep(
            "review-task",
            null,
            "alice",
            "SUCCESS",
            0,
            Map.of(),
            "ADDED",
            "adapter recommendation");
    var retainedStep =
        new ExperiencePlanStep(
            "review-task", null, "bob", "SUCCESS", 0, Map.of(), "RETAINED", null);
    var exp = experience(0.9, addedStep, retainedStep);
    var result =
        strategy.select(
            context("review-task", List.of(exp)), candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateScores()).doesNotContainKey("alice");
    assertThat(enriched.candidateScores()).containsEntry("bob", 1.0);
  }

  @Test
  void substitutedStepsExcluded() {
    var substitutedStep =
        new ExperiencePlanStep(
            "review-task",
            null,
            "alice",
            "SUCCESS",
            0,
            Map.of(),
            "SUBSTITUTED",
            "replaced original");
    var retainedStep =
        new ExperiencePlanStep(
            "review-task", null, "bob", "FAILURE", 0, Map.of(), "RETAINED", null);
    var exp = experience(0.9, substitutedStep, retainedStep);
    var result =
        strategy.select(
            context("review-task", List.of(exp)), candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateScores()).doesNotContainKey("alice");
    assertThat(enriched.candidateScores()).containsEntry("bob", 0.0);
  }

  @Test
  void similarityWeightingApplied() {
    var highSimExp = experience(0.95, step("review-task", "alice", "SUCCESS"));
    var lowSimExp = experience(0.3, step("review-task", "alice", "FAILURE"));
    var result =
        strategy.select(
            context("review-task", List.of(highSimExp, lowSimExp)),
            candidates(Set.of(), Set.of("alice")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    // (1.0*0.95 + 0.0*0.3) / (0.95+0.3) = 0.76
    assertThat(enriched.candidateScores().get("alice")).isCloseTo(0.76, within(0.01));
  }

  @Test
  void groupsPassThroughUnchanged() {
    var exp = experience(0.9, step("review-task", "alice", "SUCCESS"));
    var result =
        strategy.select(
            context("review-task", List.of(exp)),
            candidates(Set.of("managers", "reviewers"), Set.of("alice")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateGroups()).containsExactlyInAnyOrder("managers", "reviewers");
  }

  @Test
  void noMatchingTraceDataReturnsUnchanged() {
    var exp = experience(0.9, step("review-task", "charlie", "SUCCESS"));
    var result =
        strategy.select(
            context("review-task", List.of(exp)),
            candidates(Set.of("managers"), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Unchanged.class);
  }
}
