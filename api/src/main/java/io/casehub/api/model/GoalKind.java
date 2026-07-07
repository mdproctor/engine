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

/**
 * Classifies a goal kind and maps it to a terminal {@link CaseStatus}.
 *
 * <p>Implementations must provide value-based equals/hashCode — GoalKind instances serve as map
 * keys in {@link GoalBasedCompletion}.
 */
public interface GoalKind {

  String value();

  CaseStatus terminalStatus();

  GoalKind SUCCESS = StandardGoalKind.SUCCESS;
  GoalKind FAILURE = StandardGoalKind.FAILURE;

  static GoalKind of(String value, CaseStatus terminalStatus) {
    return new DefaultGoalKind(value, terminalStatus);
  }

  static GoalKind fromValue(String value) {
    try {
      return StandardGoalKind.fromValue(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown GoalKind: "
              + value
              + " — custom kinds must be created with GoalKind.of(value, terminalStatus)");
    }
  }
}
