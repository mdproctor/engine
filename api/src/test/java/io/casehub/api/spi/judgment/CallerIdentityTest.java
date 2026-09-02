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
package io.casehub.api.spi.judgment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CallerIdentityTest {

  @Test
  void ofFactory() {
    var id = CallerIdentity.of("user-42", "human");
    assertEquals("user-42", id.callerId());
    assertEquals("human", id.callerType());
    assertNull(id.trustScore());
  }

  @Test
  void ofFactoryWithTrustScore() {
    var id = CallerIdentity.of("agent-1", "a2a", 0.85);
    assertEquals("agent-1", id.callerId());
    assertEquals("a2a", id.callerType());
    assertEquals(0.85, id.trustScore());
  }

  @Test
  void requiredFieldsRejectNull() {
    assertThrows(NullPointerException.class, () -> new CallerIdentity(null, "human", null));
    assertThrows(NullPointerException.class, () -> new CallerIdentity("user-1", null, null));
  }
}
