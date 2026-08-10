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
  void constructsWithValidData() {
    var revision = new GoalRevisionProposal.RevisedGoal("g1", "new desc", "better fit");
    var proposal = new GoalRevisionProposal(List.of(revision), "test rationale");
    assertEquals(1, proposal.revisions().size());
    assertEquals("g1", proposal.revisions().get(0).goalName());
    assertEquals("new desc", proposal.revisions().get(0).revisedDescription());
  }

  @Test
  void revisionsListIsImmutable() {
    var proposal = new GoalRevisionProposal(List.of(), "rationale");
    assertThrows(
        UnsupportedOperationException.class,
        () -> proposal.revisions().add(new GoalRevisionProposal.RevisedGoal("g1", null, "reason")));
  }

  @Test
  void nullRevisionsThrows() {
    assertThrows(NullPointerException.class, () -> new GoalRevisionProposal(null, "rationale"));
  }

  @Test
  void nullGoalNameThrows() {
    assertThrows(
        NullPointerException.class,
        () -> new GoalRevisionProposal.RevisedGoal(null, "desc", "reason"));
  }

  @Test
  void nullRevisionReasonThrows() {
    assertThrows(
        NullPointerException.class, () -> new GoalRevisionProposal.RevisedGoal("g1", "desc", null));
  }

  @Test
  void nullDescriptionAllowed() {
    var revision = new GoalRevisionProposal.RevisedGoal("g1", null, "no change needed");
    assertNull(revision.revisedDescription());
  }

  @Test
  void emptyRevisionsAllowed() {
    var proposal = new GoalRevisionProposal(List.of(), "no changes needed");
    assertTrue(proposal.revisions().isEmpty());
  }
}
