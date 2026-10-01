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

import io.casehub.api.model.FailureCategory;
import io.casehub.api.spi.FailureClassificationContext;
import io.casehub.worker.api.WorkerOutcome;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultFailureClassifierTest {

  private DefaultFailureClassifier classifier;
  private FailureClassificationContext baseContext;

  @BeforeEach
  void setUp() {
    classifier = new DefaultFailureClassifier();
    baseContext =
        new FailureClassificationContext(
            "worker-1", UUID.randomUUID(), "tenant-1", "binding-1", "capability-1", 1, 3);
  }

  @Test
  void expired_classifies_as_transient() {
    var outcome = new WorkerOutcome.Expired<>("timed out");
    assertThat(classifier.classify(outcome, baseContext))
        .isInstanceOf(FailureCategory.Transient.class);
  }

  @Test
  void declined_classifies_as_knowledge() {
    var outcome = new WorkerOutcome.Declined<>("cannot handle this input");
    assertThat(classifier.classify(outcome, baseContext))
        .isInstanceOf(FailureCategory.Knowledge.class);
  }

  @Test
  void failed_with_timeout_classifies_as_transient() {
    var outcome = new WorkerOutcome.Failed<>("Connection timed out");
    assertThat(classifier.classify(outcome, baseContext))
        .isInstanceOf(FailureCategory.Transient.class);
  }

  @Test
  void failed_with_not_found_classifies_as_knowledge() {
    var outcome = new WorkerOutcome.Failed<>("Entity not found in context");
    assertThat(classifier.classify(outcome, baseContext))
        .isInstanceOf(FailureCategory.Knowledge.class);
  }

  @Test
  void failed_with_503_classifies_as_transient() {
    var outcome = new WorkerOutcome.Failed<>("HTTP 503 Service Unavailable");
    assertThat(classifier.classify(outcome, baseContext))
        .isInstanceOf(FailureCategory.Transient.class);
  }

  @Test
  void failed_with_unknown_reason_classifies_as_transient() {
    var outcome = new WorkerOutcome.Failed<>("something went wrong");
    assertThat(classifier.classify(outcome, baseContext))
        .isInstanceOf(FailureCategory.Transient.class);
  }

  @Test
  void exhausted_attempts_classifies_as_infeasible() {
    var exhaustedCtx =
        new FailureClassificationContext(
            "worker-1", UUID.randomUUID(), "tenant-1", "binding-1", "capability-1", 3, 3);
    var outcome = new WorkerOutcome.Failed<>("some error");
    assertThat(classifier.classify(outcome, exhaustedCtx))
        .isInstanceOf(FailureCategory.Infeasible.class);
  }

  @Test
  void transient_pattern_takes_precedence_over_knowledge() {
    var outcome = new WorkerOutcome.Failed<>("Connection timed out: not found");
    assertThat(classifier.classify(outcome, baseContext))
        .isInstanceOf(FailureCategory.Transient.class);
  }

  @Test
  void failed_with_null_reason_classifies_as_transient() {
    var outcome = new WorkerOutcome.Failed<>(null);
    assertThat(classifier.classify(outcome, baseContext))
        .isInstanceOf(FailureCategory.Transient.class);
  }

  @Test
  void case_insensitive_matching() {
    var outcome = new WorkerOutcome.Failed<>("CONNECTION TIMED OUT");
    assertThat(classifier.classify(outcome, baseContext))
        .isInstanceOf(FailureCategory.Transient.class);
  }
}
