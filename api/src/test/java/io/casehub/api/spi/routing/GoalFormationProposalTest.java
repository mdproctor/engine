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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.casehub.eidos.api.GoalPriority;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoalFormationProposalTest {

  @Test
  void constructsWithValidData() {
    var goal =
        new GoalFormationProposal.ProposedGoal(
            "new-goal", "A new goal", GoalPriority.SECONDARY, "emerged from experience");
    var proposal = new GoalFormationProposal(List.of(goal), "test rationale");
    assertEquals(1, proposal.goals().size());
    assertEquals("new-goal", proposal.goals().get(0).name());
  }

  @Test
  void goalsListIsImmutable() {
    var proposal = new GoalFormationProposal(List.of(), "rationale");
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            proposal
                .goals()
                .add(
                    new GoalFormationProposal.ProposedGoal("g", "d", GoalPriority.SECONDARY, "r")));
  }

  @Test
  void nullGoalsThrows() {
    assertThrows(NullPointerException.class, () -> new GoalFormationProposal(null, "rationale"));
  }

  @Test
  void nullNameThrows() {
    assertThrows(
        NullPointerException.class,
        () ->
            new GoalFormationProposal.ProposedGoal(null, "desc", GoalPriority.SECONDARY, "reason"));
  }

  @Test
  void nullPriorityAllowed() {
    var goal = new GoalFormationProposal.ProposedGoal("g", "d", null, "reason");
    assertNull(goal.suggestedPriority());
  }

  @Test
  void nullFormationReasonThrows() {
    assertThrows(
        NullPointerException.class,
        () -> new GoalFormationProposal.ProposedGoal("g", "d", GoalPriority.SECONDARY, null));
  }
}
