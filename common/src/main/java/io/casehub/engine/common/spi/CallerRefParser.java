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

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CallerRefParser {

  private static final Pattern PI_PATTERN = Pattern.compile("^case:([0-9a-fA-F-]{36})/pi:(.+)$");
  private static final Pattern GATE_PATTERN =
      Pattern.compile("^case:([0-9a-fA-F-]{36})/gate:(\\d+)$");

  private CallerRefParser() {}

  public static String encodePlanItem(UUID caseId, String planItemId) {
    return "case:" + caseId + "/pi:" + planItemId;
  }

  public static String encodeGate(UUID caseId, long gateId) {
    return "case:" + caseId + "/gate:" + gateId;
  }

  public static CallerRef parse(String raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    Matcher pi = PI_PATTERN.matcher(raw);
    if (pi.matches()) {
      try {
        return new PlanItemRef(UUID.fromString(pi.group(1)), pi.group(2));
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
    Matcher gate = GATE_PATTERN.matcher(raw);
    if (gate.matches()) {
      try {
        return new GateRef(UUID.fromString(gate.group(1)), Long.parseLong(gate.group(2)));
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
    return null;
  }

  public sealed interface CallerRef permits PlanItemRef, GateRef {
    UUID caseId();
  }

  public record PlanItemRef(UUID caseId, String planItemId) implements CallerRef {}

  public record GateRef(UUID caseId, long gateId) implements CallerRef {}
}
