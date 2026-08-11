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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PathologyConditionTest {

  @Test
  void parsesLoopDetected() {
    assertThat(PathologyCondition.fromContent("LOOP_DETECTED: agent-X repeated task 5 times"))
        .isEqualTo(PathologyCondition.LOOP_DETECTED);
  }

  @Test
  void parsesEchoChamber() {
    assertThat(PathologyCondition.fromContent("ECHO_CHAMBER: agents agreeing without progress"))
        .isEqualTo(PathologyCondition.ECHO_CHAMBER);
  }

  @Test
  void parsesConversationStall() {
    assertThat(PathologyCondition.fromContent("CONVERSATION_STALL: no messages for 5 minutes"))
        .isEqualTo(PathologyCondition.CONVERSATION_STALL);
  }

  @Test
  void parsesObligationFanOut() {
    assertThat(PathologyCondition.fromContent("OBLIGATION_FAN_OUT: 50 obligations created"))
        .isEqualTo(PathologyCondition.OBLIGATION_FAN_OUT);
  }

  @Test
  void returnsNullForNonPathologyContent() {
    assertThat(PathologyCondition.fromContent("Processing step 3 of 5")).isNull();
  }

  @Test
  void returnsNullForNull() {
    assertThat(PathologyCondition.fromContent(null)).isNull();
  }

  @Test
  void isCaseInsensitive() {
    assertThat(PathologyCondition.fromContent("loop_detected: something"))
        .isEqualTo(PathologyCondition.LOOP_DETECTED);
  }

  @Test
  void handlesLeadingWhitespace() {
    assertThat(PathologyCondition.fromContent("  ECHO_CHAMBER: detail"))
        .isEqualTo(PathologyCondition.ECHO_CHAMBER);
  }
}
