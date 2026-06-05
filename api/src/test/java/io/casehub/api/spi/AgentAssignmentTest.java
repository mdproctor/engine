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
  void assign_createsAssigned_withWorkerId() {
    final AgentAssignment assignment = AgentAssignment.assign("worker-1");

    assertThat(assignment).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) assignment).workerId()).isEqualTo("worker-1");
  }

  @Test
  void unresolvable_createsUnresolvable() {
    final AgentAssignment assignment = AgentAssignment.unresolvable();

    assertThat(assignment).isInstanceOf(AgentAssignment.Unresolvable.class);
  }

  @Test
  void escalate_createsEscalateToOversight_withCapabilityNameAndReason() {
    final AgentAssignment assignment =
        AgentAssignment.escalate("data-analysis", EscalationReason.BORDERLINE_STALEMATE);

    assertThat(assignment).isInstanceOf(AgentAssignment.EscalateToOversight.class);
    final AgentAssignment.EscalateToOversight escalation =
        (AgentAssignment.EscalateToOversight) assignment;
    assertThat(escalation.capabilityName()).isEqualTo("data-analysis");
    assertThat(escalation.reason()).isEqualTo(EscalationReason.BORDERLINE_STALEMATE);
  }

  @Test
  void patternSwitch_Assigned_extractsWorkerId() {
    final AgentAssignment assignment = AgentAssignment.assign("analyst-1");

    final String result =
        switch (assignment) {
          case AgentAssignment.Assigned a -> a.workerId();
          case AgentAssignment.Unresolvable() -> "none";
          case AgentAssignment.EscalateToOversight e -> "escalate:" + e.capabilityName();
        };

    assertThat(result).isEqualTo("analyst-1");
  }

  @Test
  void patternSwitch_Unresolvable_branchesCorrectly() {
    final AgentAssignment assignment = AgentAssignment.unresolvable();

    final String result =
        switch (assignment) {
          case AgentAssignment.Assigned a -> "assigned";
          case AgentAssignment.Unresolvable() -> "unresolvable";
          case AgentAssignment.EscalateToOversight e -> "escalate";
        };

    assertThat(result).isEqualTo("unresolvable");
  }

  @Test
  void patternSwitch_EscalateToOversight_branchesCorrectly() {
    final AgentAssignment assignment =
        AgentAssignment.escalate("review", EscalationReason.BORDERLINE_STALEMATE);

    final String result =
        switch (assignment) {
          case AgentAssignment.Assigned a -> "assigned";
          case AgentAssignment.Unresolvable() -> "unresolvable";
          case AgentAssignment.EscalateToOversight e -> "escalate:" + e.capabilityName();
        };

    assertThat(result).isEqualTo("escalate:review");
  }
}
