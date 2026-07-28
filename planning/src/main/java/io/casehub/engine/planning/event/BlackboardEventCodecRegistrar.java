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
package io.casehub.engine.planning.event;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Registers Vert.x local-only message codecs for blackboard stage events.
 *
 * <p>Stage event payloads ({@link StageActivatedEvent}, {@link StageTerminatedEvent}, {@link
 * StageCompletedEvent}) contain non-serialisable domain objects ({@link
 * io.casehub.engine.planning.stage.Stage}). Without codec registration, the Vert.x event bus cannot
 * deliver them even locally. A {@code LocalOnlyCodec} passes the object by reference — no
 * serialisation occurs. See casehubio/engine#76.
 */
@ApplicationScoped
public class BlackboardEventCodecRegistrar {

  @Inject Vertx vertx;

  void onStart(@Observes StartupEvent event) {
    var bus = vertx.getDelegate().eventBus();
    registerIfAbsent(bus, StageActivatedEvent.class, "StageActivated");
    registerIfAbsent(bus, StageTerminatedEvent.class, "StageTerminated");
    registerIfAbsent(bus, StageCompletedEvent.class, "StageCompleted");
  }

  private <T> void registerIfAbsent(
      io.vertx.core.eventbus.EventBus bus, Class<T> clazz, String name) {
    try {
      bus.registerDefaultCodec(clazz, new LocalOnlyCodec<>(name));
    } catch (IllegalStateException ignored) {
      // Already registered — Vert.x instance is shared across @QuarkusTest restarts
    }
  }

  /**
   * A no-op message codec that passes objects by reference. Only valid for local (in-VM) event bus
   * delivery — throws {@link UnsupportedOperationException} if serialisation is attempted. See
   * casehubio/engine#76.
   */
  private static final class LocalOnlyCodec<T> implements MessageCodec<T, T> {

    private final String name;

    LocalOnlyCodec(String name) {
      this.name = name;
    }

    @Override
    public void encodeToWire(Buffer buffer, T t) {
      throw new UnsupportedOperationException(
          "LocalOnlyCodec does not support wire encoding — use only with in-VM event bus");
    }

    @Override
    public T decodeFromWire(int pos, Buffer buffer) {
      throw new UnsupportedOperationException(
          "LocalOnlyCodec does not support wire decoding — use only with in-VM event bus");
    }

    @Override
    public T transform(T t) {
      return t; // pass by reference
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public byte systemCodecID() {
      return -1; // user codec
    }
  }
}
