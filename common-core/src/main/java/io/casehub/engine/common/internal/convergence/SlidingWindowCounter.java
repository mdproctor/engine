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
package io.casehub.engine.common.internal.convergence;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

public final class SlidingWindowCounter {

  private final Instant[] buffer;
  private int head;
  private int size;
  private final AtomicLong totalCount = new AtomicLong();

  public SlidingWindowCounter(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1, got: " + capacity);
    this.buffer = new Instant[capacity];
  }

  public synchronized void record(Instant timestamp) {
    buffer[head] = timestamp;
    head = (head + 1) % buffer.length;
    if (size < buffer.length) size++;
    totalCount.incrementAndGet();
  }

  public synchronized double rate(Duration window, Instant now) {
    if (size == 0) return 0.0;
    Instant cutoff = now.minus(window);
    int count = 0;
    for (int i = 0; i < size; i++) {
      int idx = (head - 1 - i + buffer.length) % buffer.length;
      if (!buffer[idx].isBefore(cutoff)) count++;
    }
    return (double) count / window.toSeconds();
  }

  public synchronized int count() {
    return size;
  }

  public long total() {
    return totalCount.get();
  }

  public synchronized void reset() {
    head = 0;
    size = 0;
    totalCount.set(0);
  }
}
