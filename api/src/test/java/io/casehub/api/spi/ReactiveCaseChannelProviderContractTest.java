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
package io.casehub.api.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.casehub.api.model.CaseChannel;
import io.casehub.qhorus.api.message.MessageType;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReactiveCaseChannelProviderContractTest {

  @Test
  void interface_hasPostToChannelDefaultMethod() throws Exception {
    assertThat(
            ReactiveCaseChannelProvider.class.getMethod(
                "postToChannel", CaseChannel.class, String.class, String.class))
        .isNotNull();
  }

  @Test
  void interface_hasPostToChannelMethodWithAllParams() throws Exception {
    assertThat(
            ReactiveCaseChannelProvider.class.getMethod(
                "postToChannel",
                CaseChannel.class,
                String.class,
                String.class,
                MessageType.class,
                String.class,
                String.class))
        .isNotNull();
  }

  @Test
  void noOp_openChannel_returnsSentinelChannel() {
    ReactiveCaseChannelProvider provider = new NoOpStub();
    UUID caseId = UUID.randomUUID();
    CaseChannel ch = provider.openChannel(caseId, "coordination").await().indefinitely();
    assertThat(ch).isNotNull();
    assertThat(ch.backendType()).isEqualTo("none");
    assertThat(ch.purpose()).isEqualTo("coordination");
  }

  @Test
  void noOp_listChannels_returnsEmptyList() {
    ReactiveCaseChannelProvider provider = new NoOpStub();
    List<CaseChannel> channels = provider.listChannels(UUID.randomUUID()).await().indefinitely();
    assertThat(channels).isEmpty();
  }

  @Test
  void noOp_postToChannel_withCommandType_completesSuccessfully() {
    ReactiveCaseChannelProvider provider = new NoOpStub();
    CaseChannel ch = provider.openChannel(UUID.randomUUID(), "p").await().indefinitely();
    assertThatNoException()
        .isThrownBy(
            () ->
                provider
                    .postToChannel(
                        ch,
                        "casehub-engine:orchestrator",
                        "cmd",
                        MessageType.COMMAND,
                        "corr-1",
                        "2026-06-01T00:00:00Z", null)
                    .await()
                    .indefinitely());
  }

  @Test
  void noOp_postToChannel_withNullParams_completesSuccessfully() {
    ReactiveCaseChannelProvider provider = new NoOpStub();
    CaseChannel ch = provider.openChannel(UUID.randomUUID(), "p").await().indefinitely();
    assertThatNoException()
        .isThrownBy(
            () ->
                provider
                    .postToChannel(ch, "system", "msg", null, null, null, null)
                    .await()
                    .indefinitely());
  }

  @Test
  void noOp_postToChannel_defaultDelegatesToTypedMethod() {
    ReactiveCaseChannelProvider provider = new NoOpStub();
    CaseChannel ch = provider.openChannel(UUID.randomUUID(), "p").await().indefinitely();
    assertThatNoException()
        .isThrownBy(() -> provider.postToChannel(ch, "alice", "hello").await().indefinitely());
  }

  @Test
  void noOp_closeChannel_completesSuccessfully() {
    ReactiveCaseChannelProvider provider = new NoOpStub();
    CaseChannel ch = provider.openChannel(UUID.randomUUID(), "p").await().indefinitely();
    assertThatNoException().isThrownBy(() -> provider.closeChannel(ch).await().indefinitely());
  }

  static class NoOpStub implements ReactiveCaseChannelProvider {

    @Override
    public Uni<CaseChannel> openChannel(UUID caseId, String purpose) {
      return Uni.createFrom()
          .item(new CaseChannel(caseId + "/" + purpose, purpose, purpose, "none", Map.of()));
    }

    @Override
    public Uni<Void> postToChannel(
        CaseChannel channel,
        String from,
        String content,
        MessageType type,
        String correlationId,
        String deadline, String target) {
      return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> closeChannel(CaseChannel channel) {
      return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<List<CaseChannel>> listChannels(UUID caseId) {
      return Uni.createFrom().item(List.of());
    }
  }
}
