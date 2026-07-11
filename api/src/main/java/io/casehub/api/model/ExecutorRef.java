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

import jakarta.annotation.Nullable;

/**
 * Shared executor identity across all coordination models.
 *
 * <p>Implemented by engine's {@code Worker} (via adapter), blocks' {@code AgentRef} variants, and
 * any future executor types.
 */
public interface ExecutorRef {

  String name();

  @Nullable
  String description();

  static ExecutorRef of(String name) {
    return new SimpleExecutorRef(name, null);
  }

  static ExecutorRef of(String name, @Nullable String description) {
    return new SimpleExecutorRef(name, description);
  }

  static ExecutorRef fromWorker(io.casehub.worker.api.Worker worker) {
    return new SimpleExecutorRef(worker.name(), worker.description());
  }
}
