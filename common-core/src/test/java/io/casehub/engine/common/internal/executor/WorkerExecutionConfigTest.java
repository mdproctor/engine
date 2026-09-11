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
package io.casehub.engine.common.internal.executor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerExecutionConfigTest {

  private WorkerExecutionConfig config;
  private final int defaultTimeout = 60000;

  @BeforeEach
  void setUp() {
    config = new WorkerExecutionConfig();
    config.defaultTimeoutMs = defaultTimeout;
  }

  @Test
  void shouldUseDefaultTimeoutWhenWorkerTimeoutIsNull() {
    int effectiveTimeout = config.getEffectiveTimeout(null);

    assertThat(effectiveTimeout).isEqualTo(defaultTimeout);
  }

  @Test
  void shouldUseWorkerSpecificTimeoutWhenProvided() {
    int workerTimeout = 120000;

    int effectiveTimeout = config.getEffectiveTimeout(workerTimeout);

    assertThat(effectiveTimeout).isEqualTo(workerTimeout);
  }

  @Test
  void shouldUseWorkerSpecificTimeoutEvenIfZero() {
    int effectiveTimeout = config.getEffectiveTimeout(0);

    assertThat(effectiveTimeout).isEqualTo(0);
  }

  @Test
  void shouldHandleVeryLargeTimeout() {
    int largeTimeout = Integer.MAX_VALUE;

    int effectiveTimeout = config.getEffectiveTimeout(largeTimeout);

    assertThat(effectiveTimeout).isEqualTo(largeTimeout);
  }
}
