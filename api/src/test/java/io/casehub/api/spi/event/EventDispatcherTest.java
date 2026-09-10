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
package io.casehub.api.spi.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventDispatcherTest {

  @Test
  void dispatchShouldAcceptAnyEvent() {
    List<Object> dispatched = new ArrayList<>();
    EventDispatcher dispatcher = dispatched::add;

    dispatcher.dispatch("test-event");
    dispatcher.dispatch(42);

    assertEquals(2, dispatched.size());
    assertEquals("test-event", dispatched.get(0));
  }

  @Test
  void typedEventWrappersShouldCarryPayload() {
    var completed = new CaseCompletedEvent("case-123");
    assertEquals("case-123", completed.caseInstanceId());

    var faulted = new CaseFaultedEvent("case-456");
    assertEquals("case-456", faulted.caseInstanceId());
  }
}
