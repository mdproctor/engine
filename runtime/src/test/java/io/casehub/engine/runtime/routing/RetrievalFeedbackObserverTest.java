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
package io.casehub.engine.internal.routing;

import io.casehub.api.spi.StepOutcomeEvent;
import io.casehub.api.spi.routing.RoutingOutcome;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// TODO #1081: restore tests when neocortex-memory-api publishes
// CbrRetrievalFeedback, CbrFeedbackOutcome, and CbrRetrievalTracker.feedback()
class RetrievalFeedbackObserverTest {

  @Test
  void noOpOnStepOutcome() {
    var observer = new RetrievalFeedbackObserver();
    observer.onStepOutcome(
        new StepOutcomeEvent(
            UUID.randomUUID(),
            "test-tenant",
            "test-case-type",
            "binding",
            "capability",
            "worker",
            RoutingOutcome.SUCCESS,
            Map.of(),
            Duration.ofSeconds(1)));
  }
}
