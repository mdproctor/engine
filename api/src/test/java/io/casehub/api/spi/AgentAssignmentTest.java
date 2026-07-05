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
package io.casehub.api.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.EscalationReason;
import org.junit.jupiter.api.Test;

class AgentAssignmentTest {

  @Test
  void assign_createsAssigned_withWorkerIdAndRationale() {
    final AgentAssignment assignment =
        AgentAssignment.assign("worker-1", "selected worker-1: load 0 (sole candidate)");

    assertThat(assignment).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) assignment).workerId()).isEqualTo("worker-1");
    assertThat(((AgentAssignment.Assigned) assignment).rationale())
        .isEqualTo("selected worker-1: load 0 (sole candidate)");
  }

  @Test
  void unresolvable_createsUnresolvable_withRationale() {
    final AgentAssignment assignment = AgentAssignment.unresolvable("no candidates available");

    assertThat(assignment).isInstanceOf(AgentAssignment.Unresolvable.class);
    assertThat(((AgentAssignment.Unresolvable) assignment).rationale())
        .isEqualTo("no candidates available");
  }

  @Test
  void escalate_createsEscalateToOversight_withCapabilityNameReasonAndRationale() {
    final AgentAssignment assignment =
        AgentAssignment.escalate(
            "data-analysis",
            EscalationReason.BORDERLINE_STALEMATE,
            "all candidates borderline for capability 'data-analysis' — oversight required");

    assertThat(assignment).isInstanceOf(AgentAssignment.EscalateToOversight.class);
    final AgentAssignment.EscalateToOversight escalation =
        (AgentAssignment.EscalateToOversight) assignment;
    assertThat(escalation.capabilityName()).isEqualTo("data-analysis");
    assertThat(escalation.reason()).isEqualTo(EscalationReason.BORDERLINE_STALEMATE);
    assertThat(escalation.rationale())
        .isEqualTo("all candidates borderline for capability 'data-analysis' — oversight required");
  }

  @Test
  void patternSwitch_Assigned_extractsWorkerId() {
    final AgentAssignment assignment =
        AgentAssignment.assign("analyst-1", "selected analyst-1: load 0 (sole candidate)");

    final String result =
        switch (assignment) {
          case AgentAssignment.Assigned a -> a.workerId();
          case AgentAssignment.Unresolvable u -> "none";
          case AgentAssignment.EscalateToOversight e -> "escalate:" + e.capabilityName();
        };

    assertThat(result).isEqualTo("analyst-1");
  }

  @Test
  void patternSwitch_Unresolvable_branchesCorrectly() {
    final AgentAssignment assignment = AgentAssignment.unresolvable("no candidates available");

    final String result =
        switch (assignment) {
          case AgentAssignment.Assigned a -> "assigned";
          case AgentAssignment.Unresolvable u -> "unresolvable";
          case AgentAssignment.EscalateToOversight e -> "escalate";
        };

    assertThat(result).isEqualTo("unresolvable");
  }

  @Test
  void patternSwitch_EscalateToOversight_branchesCorrectly() {
    final AgentAssignment assignment =
        AgentAssignment.escalate(
            "review",
            EscalationReason.BORDERLINE_STALEMATE,
            "all candidates borderline for capability 'review' — oversight required");

    final String result =
        switch (assignment) {
          case AgentAssignment.Assigned a -> "assigned";
          case AgentAssignment.Unresolvable u -> "unresolvable";
          case AgentAssignment.EscalateToOversight e -> "escalate:" + e.capabilityName();
        };

    assertThat(result).isEqualTo("escalate:review");
  }
}
