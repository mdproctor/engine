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
package io.casehub.engine.common.internal.channel;

import io.casehub.worker.api.ChannelClosedException;
import io.casehub.worker.api.DataChannel;
import io.casehub.worker.api.Exchange;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public final class InMemoryDataChannel<T> implements DataChannel<T> {

  private final String name;
  private final BlockingQueue<Exchange<T>> queue;
  private volatile boolean closed;

  public InMemoryDataChannel(String name, int capacity) {
    this.name = name;
    this.queue = new ArrayBlockingQueue<>(capacity);
  }

  @Override
  public void send(Exchange<T> exchange) {
    if (closed) {
      throw new ChannelClosedException(name);
    }
    try {
      while (!closed) {
        if (queue.offer(exchange, 100, TimeUnit.MILLISECONDS)) {
          if (closed) {
            queue.remove(exchange);
            throw new ChannelClosedException(name);
          }
          return;
        }
      }
      throw new ChannelClosedException(name);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ChannelClosedException(name);
    }
  }

  @Override
  public Exchange<T> receive() {
    while (!closed || !queue.isEmpty()) {
      try {
        Exchange<T> item = queue.poll(100, TimeUnit.MILLISECONDS);
        if (item != null) {
          return item;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public void close() {
    closed = true;
  }
}
