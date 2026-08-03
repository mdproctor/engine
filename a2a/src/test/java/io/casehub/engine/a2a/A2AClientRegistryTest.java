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
package io.casehub.engine.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.engine.common.internal.auth.AuthConfig;
import org.junit.jupiter.api.Test;

class A2AClientRegistryTest {

  private final A2AClientRegistry registry = new A2AClientRegistry();

  @Test
  void getOrCreateReturnsSameClientForSameEndpoint() {
    var client1 = registry.getOrCreate("https://agent.example.com", AuthConfig.NONE);
    var client2 = registry.getOrCreate("https://agent.example.com", AuthConfig.NONE);
    assertThat(client1).isSameAs(client2);
  }

  @Test
  void getOrCreateReturnsDifferentClientsForDifferentEndpoints() {
    var client1 = registry.getOrCreate("https://agent1.example.com", AuthConfig.NONE);
    var client2 = registry.getOrCreate("https://agent2.example.com", AuthConfig.NONE);
    assertThat(client1).isNotSameAs(client2);
  }

  @Test
  void getOrCreateNormalizesTrailingSlash() {
    var client1 = registry.getOrCreate("https://agent.example.com", AuthConfig.NONE);
    var client2 = registry.getOrCreate("https://agent.example.com/", AuthConfig.NONE);
    assertThat(client1).isSameAs(client2);
  }

  @Test
  void getOrCreateThrowsOnAuthConflict() {
    registry.getOrCreate("https://agent.example.com", AuthConfig.NONE);

    assertThatThrownBy(
            () ->
                registry.getOrCreate(
                    "https://agent.example.com", new AuthConfig(AuthConfig.AuthType.BEARER, "key")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("auth conflict");
  }

  @Test
  void evictRemovesCachedClient() {
    var client1 = registry.getOrCreate("https://agent.example.com", AuthConfig.NONE);
    registry.evict("https://agent.example.com");
    var client2 = registry.getOrCreate("https://agent.example.com", AuthConfig.NONE);
    assertThat(client1).isNotSameAs(client2);
  }

  @Test
  void evictAllowsNewAuthAfterEviction() {
    registry.getOrCreate("https://agent.example.com", AuthConfig.NONE);
    registry.evict("https://agent.example.com");

    var bearerAuth = new AuthConfig(AuthConfig.AuthType.BEARER, "new-key");
    var client = registry.getOrCreate("https://agent.example.com", bearerAuth);
    assertThat(client).isNotNull();
  }
}
