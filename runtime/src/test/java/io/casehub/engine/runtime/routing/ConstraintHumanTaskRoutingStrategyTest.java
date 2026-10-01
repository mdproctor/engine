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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.routing.ContextConstraint;
import io.casehub.api.model.routing.WorkloadConstraint;
import io.casehub.api.spi.routing.HumanTaskCandidates;
import io.casehub.api.spi.routing.HumanTaskRoutingContext;
import io.casehub.api.spi.routing.HumanTaskRoutingResult;
import io.casehub.api.spi.routing.WorkloadDataProvider;
import io.casehub.api.spi.routing.WorkloadSnapshot;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConstraintHumanTaskRoutingStrategyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final StubExpressionRegistry expressionRegistry = new StubExpressionRegistry();
  private final StubWorkloadProvider workloadProvider = new StubWorkloadProvider();
  private final ConstraintHumanTaskRoutingStrategy strategy =
      new ConstraintHumanTaskRoutingStrategy(expressionRegistry, workloadProvider);

  private HumanTaskRoutingContext context(CaseDefinition definition) {
    return new HumanTaskRoutingContext(
        UUID.randomUUID(), "review-task", "test-tenant", null, definition, List.of());
  }

  private HumanTaskCandidates candidates(Set<String> groups, Set<String> users) {
    return HumanTaskCandidates.of(groups, users);
  }

  private HumanTaskCandidates candidates(
      Set<String> groups, Set<String> users, Map<String, Set<String>> groupMembership) {
    return new HumanTaskCandidates(groups, users, groupMembership);
  }

  @Test
  void idIsConstraint() {
    assertThat(strategy.id()).isEqualTo("constraint");
  }

  @Test
  void noConstraintsReturnsUnchanged() {
    var def = CaseDefinition.builder().namespace("test").name("test").version("1.0").build();
    var result =
        strategy.select(context(def), candidates(Set.of("managers"), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Unchanged.class);
  }

  @Test
  void preferUsersBoostsScores() {
    expressionRegistry.nextResult = true;
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".always.true")
                    .preferUsers(Set.of("alice"))
                    .weight(0.8)
                    .build())
            .build();
    var result = strategy.select(context(def), candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateScores()).containsEntry("alice", 0.8);
    assertThat(enriched.candidateScores()).doesNotContainKey("bob");
  }

  @Test
  void excludeUsersRemovesCandidates() {
    expressionRegistry.nextResult = true;
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".always.true")
                    .excludeUsers(Set.of("bob"))
                    .weight(1.0)
                    .build())
            .build();
    var result = strategy.select(context(def), candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateUsers()).containsExactly("alice");
    assertThat(enriched.candidateUsers()).doesNotContain("bob");
  }

  @Test
  void excludeOnlyReturnsEnrichedNotUnchanged() {
    expressionRegistry.nextResult = true;
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".always.true")
                    .excludeUsers(Set.of("bob"))
                    .weight(1.0)
                    .build())
            .build();
    var result =
        strategy.select(context(def), candidates(Set.of("managers"), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateUsers()).containsExactly("alice");
    assertThat(enriched.candidateScores()).isEmpty();
  }

  @Test
  void falseConditionSkipped() {
    expressionRegistry.nextResult = false;
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".condition.false")
                    .excludeUsers(Set.of("alice"))
                    .weight(1.0)
                    .build())
            .build();
    var result = strategy.select(context(def), candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Unchanged.class);
  }

  @Test
  void multipleConstraintsStack() {
    expressionRegistry.nextResult = true;
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".rule.one")
                    .preferUsers(Set.of("alice"))
                    .weight(0.3)
                    .build())
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".rule.two")
                    .preferUsers(Set.of("alice"))
                    .weight(0.5)
                    .build())
            .build();
    var result = strategy.select(context(def), candidates(Set.of(), Set.of("alice")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateScores().get("alice")).isCloseTo(0.8, within(0.001));
  }

  @Test
  void allExcludedEscalates() {
    expressionRegistry.nextResult = true;
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".always.true")
                    .excludeUsers(Set.of("alice", "bob"))
                    .weight(1.0)
                    .build())
            .build();
    var result = strategy.select(context(def), candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Escalated.class);
    assertThat(((HumanTaskRoutingResult.Escalated) result).reason())
        .contains("context constraints");
  }

  @Test
  void workloadExcludesAboveThreshold() {
    workloadProvider.workload =
        Map.of(
            "alice", new WorkloadSnapshot(3),
            "bob", new WorkloadSnapshot(8));
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskWorkloadConstraint(WorkloadConstraint.builder().maxActiveTaskCount(5).build())
            .build();
    var result = strategy.select(context(def), candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateUsers()).containsExactly("alice");
  }

  @Test
  void workloadLoadBalanceScoring() {
    workloadProvider.workload =
        Map.of(
            "alice", new WorkloadSnapshot(2),
            "bob", new WorkloadSnapshot(6));
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskWorkloadConstraint(
                WorkloadConstraint.builder().loadBalanceWeight(1.0).build())
            .build();
    var result = strategy.select(context(def), candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    // alice: 1.0 * (1 - 2/6) = 0.667, bob: 1.0 * (1 - 6/6) = 0.0
    assertThat(enriched.candidateScores().get("alice")).isCloseTo(0.667, within(0.01));
    assertThat(enriched.candidateScores().get("bob")).isCloseTo(0.0, within(0.001));
  }

  @Test
  void workloadAllIdleSkipsScoring() {
    workloadProvider.workload =
        Map.of(
            "alice", new WorkloadSnapshot(0),
            "bob", new WorkloadSnapshot(0));
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskWorkloadConstraint(
                WorkloadConstraint.builder().loadBalanceWeight(0.5).build())
            .build();
    var result = strategy.select(context(def), candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Unchanged.class);
  }

  @Test
  void workloadAllExcludedEscalates() {
    workloadProvider.workload =
        Map.of(
            "alice", new WorkloadSnapshot(10),
            "bob", new WorkloadSnapshot(10));
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskWorkloadConstraint(WorkloadConstraint.builder().maxActiveTaskCount(5).build())
            .build();
    var result = strategy.select(context(def), candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Escalated.class);
    assertThat(((HumanTaskRoutingResult.Escalated) result).reason())
        .contains("workload constraints");
  }

  @Test
  void noWorkloadProviderDataSkipsWorkload() {
    // workloadProvider returns empty map by default
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskWorkloadConstraint(WorkloadConstraint.builder().maxActiveTaskCount(5).build())
            .build();
    var result = strategy.select(context(def), candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Unchanged.class);
  }

  @Test
  void preferThenExcludeCleansScores() {
    expressionRegistry.nextResult = true;
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".prefer.alice")
                    .preferUsers(Set.of("alice", "bob"))
                    .weight(0.7)
                    .build())
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".exclude.alice")
                    .excludeUsers(Set.of("alice"))
                    .weight(1.0)
                    .build())
            .build();
    var result = strategy.select(context(def), candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateUsers()).doesNotContain("alice");
    assertThat(enriched.candidateScores()).doesNotContainKey("alice");
    assertThat(enriched.candidateScores()).containsEntry("bob", 0.7);
  }

  @Test
  void combinedContextAndWorkload() {
    expressionRegistry.nextResult = true;
    workloadProvider.workload =
        Map.of(
            "alice", new WorkloadSnapshot(2),
            "bob", new WorkloadSnapshot(4));
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".always.true")
                    .preferUsers(Set.of("alice"))
                    .weight(0.5)
                    .build())
            .humanTaskWorkloadConstraint(
                WorkloadConstraint.builder().loadBalanceWeight(1.0).build())
            .build();
    var result = strategy.select(context(def), candidates(Set.of(), Set.of("alice", "bob")));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    // alice: context 0.5 + workload 1.0*(1-2/4) = 0.5 + 0.5 = 1.0
    // bob: workload 1.0*(1-4/4) = 0.0
    assertThat(enriched.candidateScores().get("alice")).isCloseTo(1.0, within(0.01));
    assertThat(enriched.candidateScores().get("bob")).isCloseTo(0.0, within(0.001));
  }

  static class StubExpressionRegistry implements ExpressionEngineRegistry {
    boolean nextResult = true;

    @Override
    public boolean evaluate(ExpressionEvaluator evaluator, CaseContext context) {
      return nextResult;
    }

    @Override
    public boolean evaluate(ExpressionEvaluator evaluator, JsonNode asNode) {
      return nextResult;
    }

    @Override
    public void validate(ExpressionEvaluator evaluator) {}

    @Override
    public ExpressionEvaluator create(String expression, String expressionLang) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void assertLanguageSupported(String expressionLang) {}

    @Override
    public java.util.List<JsonNode> transform(ExpressionEvaluator evaluator, JsonNode input) {
      return java.util.List.of(input);
    }

    @Override
    public Optional<String> extractString(ExpressionEvaluator evaluator, CaseContext context) {
      return Optional.empty();
    }
  }

  static class StubWorkloadProvider implements WorkloadDataProvider {
    Map<String, WorkloadSnapshot> workload = Map.of();

    @Override
    public String id() {
      return "stub";
    }

    @Override
    public Map<String, WorkloadSnapshot> getWorkload(Set<String> userIds, String tenancyId) {
      return workload;
    }
  }

  @Test
  void excludeGroupRemovesGroupAndMembers() {
    expressionRegistry.nextResult = true;
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".always.true")
                    .excludeGroups(Set.of("interns"))
                    .weight(1.0)
                    .build())
            .build();
    var result =
        strategy.select(
            context(def),
            candidates(
                Set.of("interns", "managers"),
                Set.of("alice"),
                Map.of(
                    "interns", Set.of("bob", "charlie"),
                    "managers", Set.of("alice"))));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateGroups()).containsExactly("managers");
    assertThat(enriched.candidateUsers()).doesNotContain("bob", "charlie");
    assertThat(enriched.candidateUsers()).contains("alice");
  }

  @Test
  void excludeGroupOverridesDirectNomination() {
    expressionRegistry.nextResult = true;
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".always.true")
                    .excludeGroups(Set.of("blocked"))
                    .weight(1.0)
                    .build())
            .build();
    var result =
        strategy.select(
            context(def),
            candidates(Set.of("blocked"), Set.of("alice"), Map.of("blocked", Set.of("alice"))));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Escalated.class);
  }

  @Test
  void preferGroupBoostsMemberScores() {
    expressionRegistry.nextResult = true;
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".always.true")
                    .preferGroups(Set.of("seniors"))
                    .weight(0.7)
                    .build())
            .build();
    var result =
        strategy.select(
            context(def),
            candidates(
                Set.of("seniors"), Set.of("alice"), Map.of("seniors", Set.of("bob", "charlie"))));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateScores()).containsEntry("bob", 0.7);
    assertThat(enriched.candidateScores()).containsEntry("charlie", 0.7);
    assertThat(enriched.candidateScores()).doesNotContainKey("alice");
  }

  @Test
  void preferWithUserInBothGroupAndUsersAppliesWeightOnce() {
    expressionRegistry.nextResult = true;
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".always.true")
                    .prefer(Set.of("seniors"), Set.of("alice"))
                    .weight(0.5)
                    .build())
            .build();
    var result =
        strategy.select(
            context(def),
            candidates(
                Set.of("seniors"), Set.of("alice"), Map.of("seniors", Set.of("alice", "bob"))));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateScores().get("alice")).isCloseTo(0.5, within(0.001));
  }

  @Test
  void allExcludedViaGroupsEscalates() {
    expressionRegistry.nextResult = true;
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".always.true")
                    .excludeGroups(Set.of("everyone"))
                    .weight(1.0)
                    .build())
            .build();
    var result =
        strategy.select(
            context(def),
            candidates(Set.of("everyone"), Set.of(), Map.of("everyone", Set.of("alice", "bob"))));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Escalated.class);
  }

  @Test
  void workloadAppliesToGroupExpandedUsers() {
    workloadProvider.workload =
        Map.of(
            "alice", new WorkloadSnapshot(2),
            "bob", new WorkloadSnapshot(8));
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskWorkloadConstraint(WorkloadConstraint.builder().maxActiveTaskCount(5).build())
            .build();
    var result =
        strategy.select(
            context(def),
            candidates(Set.of("team"), Set.of(), Map.of("team", Set.of("alice", "bob"))));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateUsers()).contains("alice");
    assertThat(enriched.candidateUsers()).doesNotContain("bob");
  }

  @Test
  void eligibleUsersInitializedFromAllUsers() {
    expressionRegistry.nextResult = true;
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .humanTaskContextConstraint(
                ContextConstraint.builder()
                    .when(".always.true")
                    .preferUsers(Set.of("bob"))
                    .weight(0.6)
                    .build())
            .build();
    var result =
        strategy.select(
            context(def),
            candidates(Set.of("team"), Set.of("alice"), Map.of("team", Set.of("bob"))));
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Enriched.class);
    var enriched = (HumanTaskRoutingResult.Enriched) result;
    assertThat(enriched.candidateScores()).containsEntry("bob", 0.6);
    assertThat(enriched.candidateUsers()).containsExactlyInAnyOrder("alice", "bob");
  }
}
