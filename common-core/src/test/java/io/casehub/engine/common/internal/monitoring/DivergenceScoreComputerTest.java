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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.engine.common.internal.history.EventLog;
import java.util.List;
import org.junit.jupiter.api.Test;

class DivergenceScoreComputerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void returns_zero_for_empty_list() {
    assertEquals(0.0, DivergenceScoreComputer.computeForCompound(List.of(), 5, 0));
  }

  @Test
  void returns_ratio_for_single_entry() {
    EventLog entry = buildEntry(0.333, 0);
    assertEquals(0.333, DivergenceScoreComputer.computeForCompound(List.of(entry), 5, 0), 0.001);
  }

  @Test
  void averages_multiple_entries() {
    EventLog e1 = buildEntry(0.0, 0);
    EventLog e2 = buildEntry(0.5, 0);
    EventLog e3 = buildEntry(1.0, 0);
    double score = DivergenceScoreComputer.computeForCompound(List.of(e1, e2, e3), 5, 0);
    assertEquals(0.5, score, 0.001);
  }

  @Test
  void window_caps_at_size() {
    EventLog old1 = buildEntry(1.0, 0);
    EventLog old2 = buildEntry(1.0, 0);
    EventLog recent1 = buildEntry(0.0, 0);
    EventLog recent2 = buildEntry(0.0, 0);
    double score =
        DivergenceScoreComputer.computeForCompound(List.of(old1, old2, recent1, recent2), 2, 0);
    assertEquals(0.0, score, 0.001);
  }

  @Test
  void filters_by_adaptation_generation() {
    EventLog gen0 = buildEntry(1.0, 0);
    EventLog gen1 = buildEntry(0.0, 1);
    double score = DivergenceScoreComputer.computeForCompound(List.of(gen0, gen1), 5, 1);
    assertEquals(0.0, score, 0.001);
  }

  @Test
  void returns_zero_when_no_entries_match_generation() {
    EventLog gen0 = buildEntry(1.0, 0);
    assertEquals(0.0, DivergenceScoreComputer.computeForCompound(List.of(gen0), 5, 1));
  }

  @Test
  void skips_entries_without_validation_metadata() {
    EventLog noMeta = new EventLog();
    noMeta.setMetadata(MAPPER.createObjectNode());
    EventLog withMeta = buildEntry(0.5, 0);
    double score = DivergenceScoreComputer.computeForCompound(List.of(noMeta, withMeta), 5, 0);
    assertEquals(0.5, score, 0.001);
  }

  private EventLog buildEntry(double ratio, int generation) {
    EventLog entry = new EventLog();
    ObjectNode metadata = MAPPER.createObjectNode();
    ObjectNode validation = MAPPER.createObjectNode();
    validation.put("divergenceRatio", ratio);
    validation.put("adaptationGeneration", generation);
    metadata.set("expectationValidation", validation);
    entry.setMetadata(metadata);
    return entry;
  }
}
