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

import io.casehub.worker.api.DataChannel;
import io.casehub.worker.api.Exchange;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataChannelRegistryTest {

  private DataChannelRegistry registry;
  private final InMemoryDataChannelFactory factory = new InMemoryDataChannelFactory();

  @BeforeEach
  void setUp() {
    registry = new DataChannelRegistry();
  }

  @Test
  void getOrCreateCreatesNewChannel() {
    UUID caseId = UUID.randomUUID();
    DataChannel<String> channel = registry.getOrCreate(caseId, "pipe", String.class, factory);

    assertThat(channel).isNotNull();
    assertThat(channel.isClosed()).isFalse();
  }

  @Test
  void getOrCreateReturnsExistingChannelOnSecondCall() {
    UUID caseId = UUID.randomUUID();
    DataChannel<String> first = registry.getOrCreate(caseId, "pipe", String.class, factory);
    DataChannel<String> second = registry.getOrCreate(caseId, "pipe", String.class, factory);

    assertThat(second).isSameAs(first);
  }

  @Test
  void getOrCreateRejectsTypeMismatch() {
    UUID caseId = UUID.randomUUID();
    registry.getOrCreate(caseId, "pipe", String.class, factory);

    assertThatThrownBy(() -> registry.getOrCreate(caseId, "pipe", Integer.class, factory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pipe")
        .hasMessageContaining("String")
        .hasMessageContaining("Integer");
  }

  @Test
  void differentCaseIdsGetIndependentChannels() {
    UUID case1 = UUID.randomUUID();
    UUID case2 = UUID.randomUUID();

    DataChannel<String> ch1 = registry.getOrCreate(case1, "pipe", String.class, factory);
    DataChannel<String> ch2 = registry.getOrCreate(case2, "pipe", String.class, factory);

    assertThat(ch2).isNotSameAs(ch1);
  }

  @Test
  void differentNamesInSameCaseGetIndependentChannels() {
    UUID caseId = UUID.randomUUID();

    DataChannel<String> ch1 = registry.getOrCreate(caseId, "input", String.class, factory);
    DataChannel<String> ch2 = registry.getOrCreate(caseId, "output", String.class, factory);

    assertThat(ch2).isNotSameAs(ch1);
  }

  @Test
  void closeByCaseClosesAllChannelsForCase() {
    UUID caseId = UUID.randomUUID();
    DataChannel<String> ch1 = registry.getOrCreate(caseId, "pipe-a", String.class, factory);
    DataChannel<String> ch2 = registry.getOrCreate(caseId, "pipe-b", String.class, factory);

    registry.closeByCase(caseId);

    assertThat(ch1.isClosed()).isTrue();
    assertThat(ch2.isClosed()).isTrue();
  }

  @Test
  void closeByCaseDoesNotAffectOtherCases() {
    UUID case1 = UUID.randomUUID();
    UUID case2 = UUID.randomUUID();
    DataChannel<String> ch1 = registry.getOrCreate(case1, "pipe", String.class, factory);
    DataChannel<String> ch2 = registry.getOrCreate(case2, "pipe", String.class, factory);

    registry.closeByCase(case1);

    assertThat(ch1.isClosed()).isTrue();
    assertThat(ch2.isClosed()).isFalse();
  }

  @Test
  void closeByCaseOnUnknownCaseIdIsNoOp() {
    registry.closeByCase(UUID.randomUUID());
  }

  @Test
  void closeByExecutionClosesTrackedChannels() {
    UUID caseId = UUID.randomUUID();
    String executionId = "exec-1";

    DataChannel<String> ch = registry.getOrCreate(caseId, "ad-hoc", String.class, factory);
    registry.trackExecution(caseId, "ad-hoc", executionId);

    registry.closeByExecution(caseId, executionId);

    assertThat(ch.isClosed()).isTrue();
  }

  @Test
  void closeByExecutionDoesNotCloseUnrelatedChannels() {
    UUID caseId = UUID.randomUUID();

    DataChannel<String> declared = registry.getOrCreate(caseId, "declared", String.class, factory);
    DataChannel<String> adHoc = registry.getOrCreate(caseId, "ad-hoc", String.class, factory);
    registry.trackExecution(caseId, "ad-hoc", "exec-1");

    registry.closeByExecution(caseId, "exec-1");

    assertThat(adHoc.isClosed()).isTrue();
    assertThat(declared.isClosed()).isFalse();
  }

  @Test
  void closeByScopeClosesTrackedChannels() {
    UUID caseId = UUID.randomUUID();
    String scopeId = "compound-1";

    DataChannel<String> ch = registry.getOrCreate(caseId, "scoped", String.class, factory);
    registry.trackScope(caseId, "scoped", scopeId);

    registry.closeByScope(caseId, scopeId);

    assertThat(ch.isClosed()).isTrue();
  }

  @Test
  void closeByScopeDoesNotCloseCaseScopedChannels() {
    UUID caseId = UUID.randomUUID();

    DataChannel<String> caseScoped = registry.getOrCreate(caseId, "case-ch", String.class, factory);
    DataChannel<String> compoundScoped =
        registry.getOrCreate(caseId, "compound-ch", String.class, factory);
    registry.trackScope(caseId, "compound-ch", "scope-1");

    registry.closeByScope(caseId, "scope-1");

    assertThat(compoundScoped.isClosed()).isTrue();
    assertThat(caseScoped.isClosed()).isFalse();
  }

  @Test
  void closedChannelIsRemovedFromRegistry() {
    UUID caseId = UUID.randomUUID();
    DataChannel<String> original = registry.getOrCreate(caseId, "pipe", String.class, factory);
    registry.closeByCase(caseId);

    DataChannel<String> recreated = registry.getOrCreate(caseId, "pipe", String.class, factory);
    assertThat(recreated).isNotSameAs(original);
    assertThat(recreated.isClosed()).isFalse();
  }

  @Test
  void channelIsUsableAfterRegistration() {
    UUID caseId = UUID.randomUUID();
    DataChannel<String> channel = registry.getOrCreate(caseId, "pipe", String.class, factory);

    channel.send(Exchange.of("test-data"));
    Exchange<String> received = channel.receive();

    assertThat(received.body()).isEqualTo("test-data");
  }

  @Test
  void getReturnsExistingChannel() {
    UUID caseId = UUID.randomUUID();
    DataChannel<String> created = registry.getOrCreate(caseId, "pipe", String.class, factory);
    DataChannel<String> retrieved = registry.get(caseId, "pipe");

    assertThat(retrieved).isSameAs(created);
  }

  @Test
  void getThrowsWhenChannelNotFound() {
    assertThatThrownBy(() -> registry.get(UUID.randomUUID(), "nonexistent"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonexistent");
  }
}
