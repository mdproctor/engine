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
package io.casehub.engine.internal.engine;

import io.casehub.engine.common.internal.event.WorkerOutcomeResolvedEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Registers Vert.x local-only message codecs for runtime event bus payloads.
 *
 * <p>{@link CaseInstance} is published on the event bus by {@link handler.CaseStatusChangedHandler}
 * for {@code casehub.case.completed} and {@code casehub.case.faulted} addresses. Without codec
 * registration the publish silently fails. A {@code LocalOnlyCodec} passes the object by reference
 * — no serialisation occurs. Fixes casehubio/engine#545.
 */
@ApplicationScoped
public class RuntimeEventCodecRegistrar {

  @Inject Vertx vertx;

  void onStart(@Observes StartupEvent event) {
    var bus = vertx.getDelegate().eventBus();
    registerIfAbsent(bus, CaseInstance.class, "CaseInstance");
    registerIfAbsent(bus, WorkerOutcomeResolvedEvent.class, "WorkerOutcomeResolved");
  }

  private <T> void registerIfAbsent(
      io.vertx.core.eventbus.EventBus bus, Class<T> clazz, String name) {
    try {
      bus.registerDefaultCodec(clazz, new LocalOnlyCodec<>(name));
    } catch (IllegalStateException ignored) {
      // Already registered — Vert.x instance is shared across @QuarkusTest restarts
    }
  }

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
      return t;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public byte systemCodecID() {
      return -1;
    }
  }
}
