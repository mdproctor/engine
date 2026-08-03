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

import io.casehub.qhorus.api.message.MessageType;
import org.jspecify.annotations.Nullable;

public record PostRequest(
    @Nullable String from,
    String content,
    MessageType type,
    @Nullable String correlationId,
    @Nullable String deadline,
    @Nullable String target,
    @Nullable String topic) {

  public static Builder builder(String content, MessageType type) {
    return new Builder(content, type);
  }

  public static final class Builder {
    private final String content;
    private final MessageType type;
    private String from;
    private String correlationId;
    private String deadline;
    private String target;
    private String topic;

    private Builder(String content, MessageType type) {
      this.content = content;
      this.type = type;
    }

    public Builder from(String from) {
      this.from = from;
      return this;
    }

    public Builder correlationId(String correlationId) {
      this.correlationId = correlationId;
      return this;
    }

    public Builder deadline(String deadline) {
      this.deadline = deadline;
      return this;
    }

    public Builder target(String target) {
      this.target = target;
      return this;
    }

    public Builder topic(String topic) {
      this.topic = topic;
      return this;
    }

    public PostRequest build() {
      return new PostRequest(from, content, type, correlationId, deadline, target, topic);
    }
  }
}
