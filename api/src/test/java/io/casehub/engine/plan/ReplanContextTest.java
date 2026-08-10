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
package io.casehub.engine.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplanContextTest {

  @Test
  void constructsWithCompletedAndFailedSteps() {
    var completed =
        List.of(
            new ReplanContext.CompletedStep("step-1", "result-1", Duration.ofSeconds(2)),
            new ReplanContext.CompletedStep("step-2", "result-2", Duration.ofSeconds(3)));
    var failed = new ReplanContext.FailedStep("step-3", "connection refused", null, 3);

    var ctx = new ReplanContext<String>(completed, failed, null, 0);

    assertThat(ctx.completedSteps()).hasSize(2);
    assertThat(ctx.failedStep().stepId()).isEqualTo("step-3");
    assertThat(ctx.failedStep().errorMessage()).isEqualTo("connection refused");
    assertThat(ctx.failedStep().retryAttempts()).isEqualTo(3);
    assertThat(ctx.replanCount()).isEqualTo(0);
  }

  @Test
  void completedStepsListIsUnmodifiable() {
    var completed =
        new ArrayList<>(List.of(new ReplanContext.CompletedStep("s1", "r1", Duration.ZERO)));
    var failed = new ReplanContext.FailedStep("s2", "err", null, 0);

    var ctx = new ReplanContext<String>(completed, failed, null, 0);

    assertThatThrownBy(
            () ->
                ctx.completedSteps()
                    .add(new ReplanContext.CompletedStep("s3", "r3", Duration.ZERO)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void failedStepRequiresStepId() {
    assertThatThrownBy(() -> new ReplanContext.FailedStep(null, "err", null, 0))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void completedStepRequiresStepId() {
    assertThatThrownBy(() -> new ReplanContext.CompletedStep(null, "r", Duration.ZERO))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void failedStepIsRequired() {
    assertThatThrownBy(() -> new ReplanContext<String>(List.of(), null, null, 0))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void emptyCompletedStepsIsValid() {
    var failed = new ReplanContext.FailedStep("s1", "err", null, 0);
    var ctx = new ReplanContext<String>(List.of(), failed, null, 0);
    assertThat(ctx.completedSteps()).isEmpty();
  }

  @Test
  void failedStepCarriesCause() {
    var cause = new RuntimeException("underlying error");
    var failed = new ReplanContext.FailedStep("s1", "err", cause, 2);
    assertThat(failed.cause()).isEqualTo(cause);
    assertThat(failed.retryAttempts()).isEqualTo(2);
  }
}
