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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaseChannelProviderContractTest {

  @Test
  void interface_hasOpenChannelMethod() throws Exception {
    assertThat(CaseChannelProvider.class.getMethod("openChannel", UUID.class, String.class))
        .isNotNull();
  }

  @Test
  void interface_hasPostToChannelDefaultMethod() throws Exception {
    assertThat(
            CaseChannelProvider.class.getMethod(
                "postToChannel", CaseChannel.class, String.class, String.class))
        .isNotNull();
  }

  @Test
  void interface_hasPostToChannelMethodWithAllParams() throws Exception {
    assertThat(
            CaseChannelProvider.class.getMethod(
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
  void interface_hasCloseChannelMethod() throws Exception {
    assertThat(CaseChannelProvider.class.getMethod("closeChannel", CaseChannel.class)).isNotNull();
  }

  @Test
  void interface_hasListChannelsMethod() throws Exception {
    assertThat(CaseChannelProvider.class.getMethod("listChannels", UUID.class)).isNotNull();
  }

  @Test
  void noOp_openChannel_returnsNonNullSentinel() {
    CaseChannelProvider provider = new NoOpStub();
    UUID caseId = UUID.randomUUID();
    CaseChannel ch = provider.openChannel(caseId, "coordination");
    assertThat(ch).isNotNull();
    assertThat(ch.backendType()).isEqualTo("none");
    assertThat(ch.purpose()).isEqualTo("coordination");
  }

  @Test
  void noOp_listChannels_returnsEmptyList() {
    CaseChannelProvider provider = new NoOpStub();
    assertThat(provider.listChannels(UUID.randomUUID())).isEmpty();
  }

  @Test
  void noOp_postToChannel_withCommandType_isNoOp() {
    CaseChannelProvider provider = new NoOpStub();
    CaseChannel ch = provider.openChannel(UUID.randomUUID(), "p");
    assertThatNoException()
        .isThrownBy(
            () ->
                provider.postToChannel(
                    ch,
                    "casehub-engine:orchestrator",
                    "cmd",
                    MessageType.COMMAND,
                    "corr-1",
                    "2026-06-01T00:00:00Z"));
  }

  @Test
  void noOp_postToChannel_withNullParams_isNoOp() {
    CaseChannelProvider provider = new NoOpStub();
    CaseChannel ch = provider.openChannel(UUID.randomUUID(), "p");
    assertThatNoException()
        .isThrownBy(() -> provider.postToChannel(ch, "system", "msg", null, null, null));
  }

  @Test
  void noOp_postToChannel_defaultDelegatesToTypedMethod() {
    CaseChannelProvider provider = new NoOpStub();
    CaseChannel ch = provider.openChannel(UUID.randomUUID(), "p");
    assertThatNoException().isThrownBy(() -> provider.postToChannel(ch, "alice", "hello"));
  }

  @Test
  void noOp_closeChannel_isNoOp() {
    CaseChannelProvider provider = new NoOpStub();
    CaseChannel ch = provider.openChannel(UUID.randomUUID(), "p");
    assertThatNoException().isThrownBy(() -> provider.closeChannel(ch));
  }

  @Test
  void noOp_closeChannel_unknownChannel_isNoOp() {
    CaseChannelProvider provider = new NoOpStub();
    CaseChannel unknown = new CaseChannel("unknown-id", "n", "p", "none", Map.of());
    assertThatNoException().isThrownBy(() -> provider.closeChannel(unknown));
  }

  /**
   * CONTRACT: openChannel must be idempotent (get-or-create semantics). Calling it more than once
   * for the same caseId + purpose must not throw and must return a usable channel. This is required
   * because WorkerScheduleEventHandler calls openChannel on every worker dispatch, and the case
   * channel was already opened by CaseStartedEventHandler. Real implementations (e.g. Qhorus) must
   * enforce this contract. See casehubio/engine#323.
   */
  @Test
  void openChannel_repeated_calls_same_caseId_and_purpose_are_idempotent() {
    CaseChannelProvider provider = new NoOpStub();
    UUID caseId = UUID.randomUUID();

    CaseChannel first = provider.openChannel(caseId, "worker:my-worker");
    CaseChannel second = provider.openChannel(caseId, "worker:my-worker");

    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    // Both channels must be usable for posting without exception
    assertThatNoException()
        .isThrownBy(
            () ->
                provider.postToChannel(
                    second, "casehub-engine:orchestrator", "cmd", MessageType.COMMAND, null, null));
  }

  static class NoOpStub implements CaseChannelProvider {
    @Override
    public CaseChannel openChannel(UUID caseId, String purpose) {
      return new CaseChannel(caseId + "/" + purpose, purpose, purpose, "none", Map.of());
    }

    @Override
    public void postToChannel(
        CaseChannel ch,
        String from,
        String content,
        MessageType type,
        String correlationId,
        String deadline) {}

    @Override
    public void closeChannel(CaseChannel ch) {}

    @Override
    public List<CaseChannel> listChannels(UUID caseId) {
      return List.of();
    }
  }
}
