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
package io.casehub.engine.common.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CallerRefParserTest {

  private static final UUID CASE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

  @Test
  void encodePlanItem_produces_correct_format() {
    String ref = CallerRefParser.encodePlanItem(CASE_ID, "pi-001");
    assertThat(ref).isEqualTo("case:550e8400-e29b-41d4-a716-446655440000/pi:pi-001");
  }

  @Test
  void encodeGate_produces_correct_format() {
    String ref = CallerRefParser.encodeGate(CASE_ID, 42L);
    assertThat(ref).isEqualTo("case:550e8400-e29b-41d4-a716-446655440000/gate:42");
  }

  @Test
  void parse_planItem_ref() {
    var ref = CallerRefParser.parse("case:550e8400-e29b-41d4-a716-446655440000/pi:pi-001");
    assertThat(ref).isInstanceOf(CallerRefParser.PlanItemRef.class);
    var pi = (CallerRefParser.PlanItemRef) ref;
    assertThat(pi.caseId()).isEqualTo(CASE_ID);
    assertThat(pi.planItemId()).isEqualTo("pi-001");
  }

  @Test
  void parse_gate_ref() {
    var ref = CallerRefParser.parse("case:550e8400-e29b-41d4-a716-446655440000/gate:42");
    assertThat(ref).isInstanceOf(CallerRefParser.GateRef.class);
    var gate = (CallerRefParser.GateRef) ref;
    assertThat(gate.caseId()).isEqualTo(CASE_ID);
    assertThat(gate.gateId()).isEqualTo(42L);
  }

  @Test
  void parse_null_returns_null() {
    assertThat(CallerRefParser.parse(null)).isNull();
  }

  @Test
  void parse_invalid_format_returns_null() {
    assertThat(CallerRefParser.parse("not-a-caller-ref")).isNull();
    assertThat(CallerRefParser.parse("case:invalid-uuid/pi:001")).isNull();
    assertThat(CallerRefParser.parse("")).isNull();
  }

  @Test
  void roundtrip_planItem() {
    String encoded = CallerRefParser.encodePlanItem(CASE_ID, "pi-001");
    var parsed = (CallerRefParser.PlanItemRef) CallerRefParser.parse(encoded);
    assertThat(parsed.caseId()).isEqualTo(CASE_ID);
    assertThat(parsed.planItemId()).isEqualTo("pi-001");
  }

  @Test
  void roundtrip_gate() {
    String encoded = CallerRefParser.encodeGate(CASE_ID, 99L);
    var parsed = (CallerRefParser.GateRef) CallerRefParser.parse(encoded);
    assertThat(parsed.caseId()).isEqualTo(CASE_ID);
    assertThat(parsed.gateId()).isEqualTo(99L);
  }
}
