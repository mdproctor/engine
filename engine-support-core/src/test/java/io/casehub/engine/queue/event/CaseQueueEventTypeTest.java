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
package io.casehub.engine.queue.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.platform.api.view.ViewEventType;
import org.junit.jupiter.api.Test;

class CaseQueueEventTypeTest {

  @Test
  void mapsAllViewEventTypes() {
    assertThat(CaseQueueEventType.from(ViewEventType.ADDED)).isEqualTo(CaseQueueEventType.ADDED);
    assertThat(CaseQueueEventType.from(ViewEventType.REMOVED))
        .isEqualTo(CaseQueueEventType.REMOVED);
    assertThat(CaseQueueEventType.from(ViewEventType.CHANGED))
        .isEqualTo(CaseQueueEventType.CHANGED);
  }
}
