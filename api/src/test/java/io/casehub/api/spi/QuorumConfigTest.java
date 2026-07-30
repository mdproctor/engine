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

import io.casehub.api.model.OnThresholdReached;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuorumConfigTest {

  @Test
  void valid_config() {
    var config = new QuorumConfig(3, 2, null, false);
    assertEquals(3, config.instances());
    assertEquals(2, config.required());
    assertNull(config.onThresholdReached());
    assertFalse(config.allowSameAssignee());
  }

  @Test
  void instances_below_two_throws() {
    assertThrows(IllegalArgumentException.class, () -> new QuorumConfig(1, 1, null, false));
  }

  @Test
  void required_zero_throws() {
    assertThrows(IllegalArgumentException.class, () -> new QuorumConfig(3, 0, null, false));
  }

  @Test
  void required_exceeds_instances_throws() {
    assertThrows(IllegalArgumentException.class, () -> new QuorumConfig(3, 4, null, false));
  }

  @Test
  void required_equals_instances_is_unanimous() {
    var config = new QuorumConfig(3, 3, null, false);
    assertEquals(3, config.required());
  }

  @Test
  void onThresholdReached_cancel() {
    var config = new QuorumConfig(5, 3, OnThresholdReached.CANCEL, false);
    assertEquals(OnThresholdReached.CANCEL, config.onThresholdReached());
  }

  @Test
  void allowSameAssignee_true() {
    var config = new QuorumConfig(3, 2, null, true);
    assertTrue(config.allowSameAssignee());
  }

    @Test
    void majority_of_5_requires_3() {
        var config = QuorumConfig.majority(5);
        assertEquals(5, config.instances());
        assertEquals(3, config.required());
    }

    @Test
    void majority_of_3_requires_2() {
        var config = QuorumConfig.majority(3);
        assertEquals(3, config.instances());
        assertEquals(2, config.required());
    }

    @Test
    void majority_of_2_requires_2() {
        var config = QuorumConfig.majority(2);
        assertEquals(2, config.instances());
        assertEquals(2, config.required());
    }

    @Test
    void unanimous_of_3() {
        var config = QuorumConfig.unanimous(3);
        assertEquals(3, config.instances());
        assertEquals(3, config.required());
    }

    @Test
    void atLeast_2_of_5() {
        var config = QuorumConfig.atLeast(5, 2);
        assertEquals(5, config.instances());
        assertEquals(2, config.required());
    }
}
