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
package io.casehub.engine.common.internal.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.engine.common.internal.history.EventLog;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdaptationCostComputerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private EventLog adaptationEvent(String compoundId, int newSteps, int prevSteps) {
    var event = new EventLog();
    event.setTimestamp(Instant.now());
    ObjectNode meta = MAPPER.createObjectNode();
    meta.put("compoundId", compoundId);
    meta.put("newStepCount", newSteps);
    meta.put("previousStepCount", prevSteps);
    event.setMetadata(meta);
    return event;
  }

  @Test
  void empty_events_returns_zero_cost() {
    var summary = AdaptationCostComputer.computeForCompound(List.of(), "comp-1");
    assertThat(summary.adaptationCount()).isZero();
    assertThat(summary.totalStepsProduced()).isZero();
    assertThat(summary.totalStepsObsoleted()).isZero();
  }

  @Test
  void single_adaptation_counted() {
    var summary =
        AdaptationCostComputer.computeForCompound(
            List.of(adaptationEvent("comp-1", 3, 2)), "comp-1");
    assertThat(summary.adaptationCount()).isEqualTo(1);
    assertThat(summary.totalStepsProduced()).isEqualTo(3);
    assertThat(summary.totalStepsObsoleted()).isEqualTo(2);
  }

  @Test
  void filters_by_compoundId() {
    var events =
        List.of(
            adaptationEvent("comp-1", 3, 2),
            adaptationEvent("comp-2", 5, 4),
            adaptationEvent("comp-1", 2, 1));
    var summary = AdaptationCostComputer.computeForCompound(events, "comp-1");
    assertThat(summary.adaptationCount()).isEqualTo(2);
    assertThat(summary.totalStepsProduced()).isEqualTo(5);
    assertThat(summary.totalStepsObsoleted()).isEqualTo(3);
  }

  @Test
  void handles_missing_metadata_fields() {
    var event = new EventLog();
    event.setTimestamp(Instant.now());
    ObjectNode meta = MAPPER.createObjectNode();
    meta.put("compoundId", "comp-1");
    event.setMetadata(meta);
    var summary = AdaptationCostComputer.computeForCompound(List.of(event), "comp-1");
    assertThat(summary.adaptationCount()).isEqualTo(1);
    assertThat(summary.totalStepsProduced()).isZero();
    assertThat(summary.totalStepsObsoleted()).isZero();
  }

  @Test
  void null_metadata_skipped() {
    var event = new EventLog();
    event.setTimestamp(Instant.now());
    var summary = AdaptationCostComputer.computeForCompound(List.of(event), "comp-1");
    assertThat(summary.adaptationCount()).isZero();
  }
}
