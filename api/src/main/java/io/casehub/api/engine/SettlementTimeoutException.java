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
package io.casehub.api.engine;

import java.time.Duration;
import java.util.UUID;

/** Thrown when awaiting a child case or worker outcome times out before settlement. */
public class SettlementTimeoutException extends RuntimeException {

  private final UUID targetId;
  private final Duration timeout;

  public SettlementTimeoutException(UUID targetId, Duration timeout) {
    super("Settlement timed out after " + timeout + " for " + targetId);
    this.targetId = targetId;
    this.timeout = timeout;
  }

  public UUID getTargetId() {
    return targetId;
  }

  public Duration getTimeout() {
    return timeout;
  }
}
