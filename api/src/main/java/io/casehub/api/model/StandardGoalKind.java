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
package io.casehub.api.model;

public enum StandardGoalKind implements GoalKind {
  SUCCESS("success", CaseStatus.COMPLETED),
  FAILURE("failure", CaseStatus.FAULTED);

  private final String value;
  private final CaseStatus terminalStatus;

  StandardGoalKind(String value, CaseStatus terminalStatus) {
    this.value = value;
    this.terminalStatus = terminalStatus;
  }

  @Override
  public String value() {
    return value;
  }

  @Override
  public CaseStatus terminalStatus() {
    return terminalStatus;
  }

  @Override
  public String toString() {
    return value;
  }

  public static StandardGoalKind fromValue(String value) {
    for (StandardGoalKind kind : values()) {
      if (kind.value.equals(value)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("Unknown StandardGoalKind: " + value);
  }
}
