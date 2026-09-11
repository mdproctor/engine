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
package io.casehub.resilience.deadletter;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Immutable filter specification for {@link DeadLetterQueue#query(DeadLetterQuery)}. Build via the
 * static factory methods; filters are AND-combined.
 */
public final class DeadLetterQuery {

  private final Optional<DeadLetterStatus> status;
  private final Optional<String> workerId;
  private final Optional<Instant> arrivedAfter;

  private DeadLetterQuery(
      Optional<DeadLetterStatus> status,
      Optional<String> workerId,
      Optional<Instant> arrivedAfter) {
    this.status = status;
    this.workerId = workerId;
    this.arrivedAfter = arrivedAfter;
  }

  /** Match every entry. */
  public static DeadLetterQuery all() {
    return new DeadLetterQuery(Optional.empty(), Optional.empty(), Optional.empty());
  }

  /** Match entries with the given status. */
  public static DeadLetterQuery withStatus(DeadLetterStatus status) {
    return new DeadLetterQuery(Optional.of(status), Optional.empty(), Optional.empty());
  }

  /** Match entries for a specific worker. */
  public static DeadLetterQuery forWorker(String workerId) {
    return new DeadLetterQuery(Optional.empty(), Optional.of(workerId), Optional.empty());
  }

  /** Match entries that arrived after the given instant. */
  public static DeadLetterQuery arrivedAfter(Instant cutoff) {
    return new DeadLetterQuery(Optional.empty(), Optional.empty(), Optional.of(cutoff));
  }

  /** Builds a predicate representing all active filters. */
  Predicate<DeadLetterEntry> toPredicate() {
    Predicate<DeadLetterEntry> pred = e -> true;
    if (status.isPresent()) {
      pred = pred.and(e -> e.status() == status.get());
    }
    if (workerId.isPresent()) {
      pred = pred.and(e -> workerId.get().equals(e.workerId()));
    }
    if (arrivedAfter.isPresent()) {
      pred = pred.and(e -> e.arrivedAt().isAfter(arrivedAfter.get()));
    }
    return pred;
  }
}
