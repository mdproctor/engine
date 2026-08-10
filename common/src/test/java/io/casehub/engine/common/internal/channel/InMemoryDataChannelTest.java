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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.worker.api.ChannelClosedException;
import io.casehub.worker.api.DataChannel;
import io.casehub.worker.api.Exchange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InMemoryDataChannelTest {

  @Test
  void sendAndReceiveSingleExchange() {
    DataChannel<String> channel = new InMemoryDataChannel<>("test", 16);
    Exchange<String> sent = Exchange.of("hello");

    channel.send(sent);
    Exchange<String> received = channel.receive();

    assertThat(received).isEqualTo(sent);
  }

  @Test
  void receivePreservesFifoOrder() {
    DataChannel<String> channel = new InMemoryDataChannel<>("test", 16);
    Exchange<String> first = Exchange.of("first");
    Exchange<String> second = Exchange.of("second");
    Exchange<String> third = Exchange.of("third");

    channel.send(first);
    channel.send(second);
    channel.send(third);

    assertThat(channel.receive()).isEqualTo(first);
    assertThat(channel.receive()).isEqualTo(second);
    assertThat(channel.receive()).isEqualTo(third);
  }

  @Test
  void receiveReturnsNullWhenClosedAndEmpty() {
    DataChannel<String> channel = new InMemoryDataChannel<>("test", 16);
    channel.close();

    assertThat(channel.receive()).isNull();
  }

  @Test
  void receiveDrainsRemainingItemsThenReturnsNull() {
    DataChannel<String> channel = new InMemoryDataChannel<>("test", 16);
    Exchange<String> exchange = Exchange.of("data");

    channel.send(exchange);
    channel.close();

    assertThat(channel.receive()).isEqualTo(exchange);
    assertThat(channel.receive()).isNull();
  }

  @Test
  void sendThrowsWhenClosed() {
    DataChannel<String> channel = new InMemoryDataChannel<>("test", 16);
    channel.close();

    assertThatThrownBy(() -> channel.send(Exchange.of("data")))
        .isInstanceOf(ChannelClosedException.class)
        .hasMessageContaining("test");
  }

  @Test
  void isClosedReflectsState() {
    DataChannel<String> channel = new InMemoryDataChannel<>("test", 16);

    assertThat(channel.isClosed()).isFalse();
    channel.close();
    assertThat(channel.isClosed()).isTrue();
  }

  @Test
  void closeIsIdempotent() {
    DataChannel<String> channel = new InMemoryDataChannel<>("test", 16);
    channel.close();
    channel.close();

    assertThat(channel.isClosed()).isTrue();
  }

  @Test
  void nullBodyExchangeIsSupported() {
    DataChannel<String> channel = new InMemoryDataChannel<>("test", 16);
    Exchange<String> signalOnly = Exchange.of(null, Map.of("signal", "ping"));

    channel.send(signalOnly);
    Exchange<String> received = channel.receive();

    assertThat(received.body()).isNull();
    assertThat(received.headers()).containsEntry("signal", "ping");
  }

  @Test
  void headersAndPropertiesPreservedAcrossSendReceive() {
    DataChannel<String> channel = new InMemoryDataChannel<>("test", 16);
    Exchange<String> exchange =
        new Exchange<>("body", Map.of("correlationId", "abc-123"), Map.of("loopCount", 3));

    channel.send(exchange);
    Exchange<String> received = channel.receive();

    assertThat(received.headers()).containsEntry("correlationId", "abc-123");
    assertThat(received.properties()).containsEntry("loopCount", 3);
  }

  @Test
  void sendBlocksWhenQueueFull() throws Exception {
    final int capacity = 2;
    DataChannel<String> channel = new InMemoryDataChannel<>("test", capacity);

    channel.send(Exchange.of("a"));
    channel.send(Exchange.of("b"));

    AtomicBoolean sendCompleted = new AtomicBoolean(false);
    CountDownLatch sendStarted = new CountDownLatch(1);

    Thread producer =
        Thread.ofVirtual()
            .start(
                () -> {
                  sendStarted.countDown();
                  channel.send(Exchange.of("c"));
                  sendCompleted.set(true);
                });

    sendStarted.await(1, TimeUnit.SECONDS);
    Thread.sleep(100);
    assertThat(sendCompleted.get()).isFalse();

    channel.receive();
    producer.join(1000);
    assertThat(sendCompleted.get()).isTrue();
  }

  @Test
  void concurrentProducerConsumer() throws Exception {
    final int messageCount = 1000;
    DataChannel<Integer> channel = new InMemoryDataChannel<>("test", 32);
    List<Integer> received = new ArrayList<>();

    Thread consumer =
        Thread.ofVirtual()
            .start(
                () -> {
                  while (true) {
                    Exchange<Integer> ex = channel.receive();
                    if (ex == null) break;
                    received.add(ex.body());
                  }
                });

    try (ExecutorService producers = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < messageCount; i++) {
        final int val = i;
        producers.submit(() -> channel.send(Exchange.of(val)));
      }
    }

    channel.close();
    consumer.join(5000);

    assertThat(received).hasSize(messageCount);
    assertThat(received)
        .containsExactlyInAnyOrderElementsOf(
            java.util.stream.IntStream.range(0, messageCount).boxed().toList());
  }

  @Test
  void closeUnblocksPendingReceive() throws Exception {
    DataChannel<String> channel = new InMemoryDataChannel<>("test", 16);
    AtomicReference<Exchange<String>> result = new AtomicReference<>(Exchange.of("sentinel"));

    Thread consumer =
        Thread.ofVirtual()
            .start(
                () -> {
                  result.set(channel.receive());
                });

    Thread.sleep(100);
    channel.close();
    consumer.join(2000);

    assertThat(result.get()).isNull();
  }

  @Test
  void closeUnblocksPendingSendWithException() throws Exception {
    final int capacity = 1;
    DataChannel<String> channel = new InMemoryDataChannel<>("test", capacity);
    channel.send(Exchange.of("fill"));

    AtomicReference<Throwable> caughtException = new AtomicReference<>();
    CountDownLatch sendStarted = new CountDownLatch(1);

    Thread producer =
        Thread.ofVirtual()
            .start(
                () -> {
                  sendStarted.countDown();
                  try {
                    channel.send(Exchange.of("blocked"));
                  } catch (final Throwable t) {
                    caughtException.set(t);
                  }
                });

    sendStarted.await(1, TimeUnit.SECONDS);
    Thread.sleep(100);
    channel.close();
    producer.join(2000);

    assertThat(caughtException.get()).isInstanceOf(ChannelClosedException.class);
  }
}
