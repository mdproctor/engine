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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GoalRevisionProposalTest {

  @Test
  void constructsWithReviseAction() {
    var revision =
        new GoalRevisionProposal.RevisedGoal(
            "g1", GoalRevisionAction.REVISE, "new desc", "better fit");
    var proposal = new GoalRevisionProposal(List.of(revision), "test rationale");
    assertEquals(1, proposal.revisions().size());
    assertEquals("g1", proposal.revisions().get(0).goalName());
    assertEquals(GoalRevisionAction.REVISE, proposal.revisions().get(0).action());
    assertEquals("new desc", proposal.revisions().get(0).revisedDescription());
  }

  @Test
  void reviseActionRequiresDescription() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GoalRevisionProposal.RevisedGoal("g1", GoalRevisionAction.REVISE, null, "reason"));
  }

  @Test
  void abandonActionAllowsNullDescription() {
    var revision =
        new GoalRevisionProposal.RevisedGoal(
            "g1", GoalRevisionAction.ABANDON, null, "no longer relevant");
    assertNull(revision.revisedDescription());
    assertEquals(GoalRevisionAction.ABANDON, revision.action());
  }

  @Test
  void completeActionAllowsNullDescription() {
    var revision =
        new GoalRevisionProposal.RevisedGoal(
            "g1", GoalRevisionAction.COMPLETE, null, "goal achieved");
    assertNull(revision.revisedDescription());
    assertEquals(GoalRevisionAction.COMPLETE, revision.action());
  }

  @Test
  void abandonActionAcceptsInformationalDescription() {
    var revision =
        new GoalRevisionProposal.RevisedGoal(
            "g1", GoalRevisionAction.ABANDON, "was trying X", "unachievable");
    assertEquals("was trying X", revision.revisedDescription());
  }

  @Test
  void nullActionThrows() {
    assertThrows(
        NullPointerException.class,
        () -> new GoalRevisionProposal.RevisedGoal("g1", null, "desc", "reason"));
  }

  @Test
  void nullGoalNameThrows() {
    assertThrows(
        NullPointerException.class,
        () ->
            new GoalRevisionProposal.RevisedGoal(
                null, GoalRevisionAction.REVISE, "desc", "reason"));
  }

  @Test
  void nullRevisionReasonThrows() {
    assertThrows(
        NullPointerException.class,
        () -> new GoalRevisionProposal.RevisedGoal("g1", GoalRevisionAction.REVISE, "desc", null));
  }

  @Test
  void revisionsListIsImmutable() {
    var proposal = new GoalRevisionProposal(List.of(), "rationale");
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            proposal
                .revisions()
                .add(
                    new GoalRevisionProposal.RevisedGoal(
                        "g1", GoalRevisionAction.ABANDON, null, "reason")));
  }

  @Test
  void nullRevisionsThrows() {
    assertThrows(NullPointerException.class, () -> new GoalRevisionProposal(null, "rationale"));
  }

  @Test
  void emptyRevisionsAllowed() {
    var proposal = new GoalRevisionProposal(List.of(), "no changes needed");
    assertTrue(proposal.revisions().isEmpty());
  }
}
