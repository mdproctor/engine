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
package io.casehub.engine.internal.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.RecoveryLevel;
import io.casehub.api.spi.recovery.ErrorClassificationContext;
import io.casehub.worker.api.FailureClass;
import io.casehub.worker.api.WorkerOutcome;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultErrorClassifierTest {

  private final DefaultErrorClassifier classifier = new DefaultErrorClassifier();

  @Test
  void attemptCountAboveThresholdReturnsReasoningRegardlessOfHint() {
    var ctx = contextWith(FailureClass.TRANSIENT, new WorkerOutcome.Expired<>("timeout"), 4);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.REASONING);
  }

  @Test
  void transientHintReturnsTransient() {
    var ctx = contextWith(FailureClass.TRANSIENT, new WorkerOutcome.Failed<>("err"), 1);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.TRANSIENT);
  }

  @Test
  void reasoningHintReturnsReasoning() {
    var ctx = contextWith(FailureClass.REASONING, new WorkerOutcome.Failed<>("err"), 1);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.REASONING);
  }

  @Test
  void fundamentalHintReturnsFundamental() {
    var ctx = contextWith(FailureClass.FUNDAMENTAL, new WorkerOutcome.Failed<>("err"), 1);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.FUNDAMENTAL);
  }

  @Test
  void expiredWithNoHintReturnsTransient() {
    var ctx = contextWith(null, new WorkerOutcome.Expired<>("timeout"), 1);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.TRANSIENT);
  }

  @Test
  void declinedWithNoHintReturnsReasoning() {
    var ctx = contextWith(null, new WorkerOutcome.Declined<>("not suitable"), 1);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.REASONING);
  }

  @Test
  void failedWithTimeoutPatternReturnsTransient() {
    var ctx = contextWith(null, new WorkerOutcome.Failed<>("Connection timeout after 30s"), 1);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.TRANSIENT);
  }

  @Test
  void failedWithConnectionRefusedReturnsTransient() {
    var ctx = contextWith(null, new WorkerOutcome.Failed<>("connection refused"), 1);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.TRANSIENT);
  }

  @Test
  void failedWith503ReturnsTransient() {
    var ctx = contextWith(null, new WorkerOutcome.Failed<>("HTTP 503 Service Unavailable"), 1);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.TRANSIENT);
  }

  @Test
  void failedWith429ReturnsTransient() {
    var ctx = contextWith(null, new WorkerOutcome.Failed<>("HTTP 429 Too Many Requests"), 1);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.TRANSIENT);
  }

  @Test
  void failedWithUnknownReasonReturnsReasoning() {
    var ctx = contextWith(null, new WorkerOutcome.Failed<>("invalid input format"), 1);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.REASONING);
  }

  @Test
  void failedWithNullReasonReturnsReasoning() {
    var ctx = contextWith(null, new WorkerOutcome.Failed<>(null), 1);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.REASONING);
  }

  @Test
  void idReturnsHeuristic() {
    assertThat(classifier.id()).isEqualTo("heuristic");
  }

  @Test
  void transientWithNonIdempotentUpgradesToReasoning() {
    var def =
        definitionWithSideEffect(
            "binding-1", io.casehub.api.model.SideEffectClassification.NON_IDEMPOTENT);
    var ctx = contextWithDefinition(null, new WorkerOutcome.Expired<>("timeout"), 1, def);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.REASONING);
  }

  @Test
  void transientWithIdempotentStaysTransient() {
    var def =
        definitionWithSideEffect(
            "binding-1", io.casehub.api.model.SideEffectClassification.IDEMPOTENT);
    var ctx = contextWithDefinition(null, new WorkerOutcome.Expired<>("timeout"), 1, def);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.TRANSIENT);
  }

  @Test
  void transientWithUnknownStaysTransient() {
    var def =
        definitionWithSideEffect(
            "binding-1", io.casehub.api.model.SideEffectClassification.UNKNOWN);
    var ctx = contextWithDefinition(null, new WorkerOutcome.Expired<>("timeout"), 1, def);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.TRANSIENT);
  }

  @Test
  void nonIdempotentDoesNotAffectNonTransient() {
    var def =
        definitionWithSideEffect(
            "binding-1", io.casehub.api.model.SideEffectClassification.NON_IDEMPOTENT);
    var ctx =
        contextWithDefinition(
            io.casehub.worker.api.FailureClass.REASONING,
            new WorkerOutcome.Failed<>("err"),
            1,
            def);
    assertThat(classifier.classify(ctx)).isEqualTo(RecoveryLevel.REASONING);
  }

  private io.casehub.api.model.CaseDefinition definitionWithSideEffect(
      String bindingName, io.casehub.api.model.SideEffectClassification classification) {
    return io.casehub.api.model.CaseDefinition.builder()
        .namespace("test")
        .name("test")
        .version("1.0")
        .capabilities(io.casehub.worker.api.Capability.of("cap1", ".", "."))
        .bindings(
            io.casehub.api.model.Binding.builder()
                .name(bindingName)
                .capability(io.casehub.worker.api.Capability.of("cap1", ".", "."))
                .on(new io.casehub.api.model.ContextChangeTrigger(".ready"))
                .sideEffectClassification(classification)
                .build())
        .build();
  }

  private ErrorClassificationContext contextWithDefinition(
      FailureClass hint,
      WorkerOutcome<?> outcome,
      int attemptCount,
      io.casehub.api.model.CaseDefinition def) {
    return new ErrorClassificationContext(
        UUID.randomUUID(),
        "tenant-1",
        "binding-1",
        "worker-1",
        "capability-1",
        outcome,
        hint,
        attemptCount,
        def);
  }

  private ErrorClassificationContext contextWith(
      FailureClass hint, WorkerOutcome<?> outcome, int attemptCount) {
    return new ErrorClassificationContext(
        UUID.randomUUID(),
        "tenant-1",
        "binding-1",
        "worker-1",
        "capability-1",
        outcome,
        hint,
        attemptCount,
        null);
  }
}
