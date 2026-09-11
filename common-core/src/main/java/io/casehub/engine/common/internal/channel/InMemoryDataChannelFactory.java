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

import io.casehub.api.spi.DataChannelFactory;
import io.casehub.worker.api.DataChannel;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@DefaultBean
@ApplicationScoped
public class InMemoryDataChannelFactory implements DataChannelFactory {

  private static final int DEFAULT_BUFFER_SIZE = 1024;

  @ConfigProperty(name = "casehub.engine.channel.send-timeout-ms")
  Optional<Long> sendTimeoutMs;

  @Override
  public <T> DataChannel<T> create(String name, Class<T> recordType, UUID caseId) {
    long timeout = sendTimeoutMs != null ? sendTimeoutMs.orElse(0L) : 0L;
    return new InMemoryDataChannel<>(name, DEFAULT_BUFFER_SIZE, timeout);
  }

  @Override
  public String id() {
    return "in-memory";
  }
}
