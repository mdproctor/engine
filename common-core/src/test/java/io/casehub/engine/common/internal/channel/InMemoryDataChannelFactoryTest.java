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

import io.casehub.worker.api.DataChannel;
import io.casehub.worker.api.Exchange;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryDataChannelFactoryTest {

  private final InMemoryDataChannelFactory factory = new InMemoryDataChannelFactory();

  @Test
  void createsWorkingDataChannel() {
    DataChannel<String> channel = factory.create("test-channel", String.class, UUID.randomUUID());

    channel.send(Exchange.of("hello"));
    Exchange<String> received = channel.receive();

    assertThat(received.body()).isEqualTo("hello");
    channel.close();
  }

  @Test
  void hasInMemoryStrategyId() {
    assertThat(factory.id()).isEqualTo("in-memory");
  }

  @Test
  void createsIndependentChannels() {
    UUID caseId = UUID.randomUUID();
    DataChannel<String> channel1 = factory.create("ch1", String.class, caseId);
    DataChannel<String> channel2 = factory.create("ch2", String.class, caseId);

    channel1.send(Exchange.of("for-ch1"));

    assertThat(channel2.isClosed()).isFalse();
    channel1.close();
    assertThat(channel2.isClosed()).isFalse();

    channel2.close();
  }
}
