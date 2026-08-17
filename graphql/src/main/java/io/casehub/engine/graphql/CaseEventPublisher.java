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
package io.casehub.engine.graphql;

import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class CaseEventPublisher {

  private final List<io.smallrye.mutiny.subscription.MultiEmitter<? super CaseLifecycleEvent>>
      lifecycleEmitters = new CopyOnWriteArrayList<>();
  private final List<io.smallrye.mutiny.subscription.MultiEmitter<? super CaseContextChangedEvent>>
      contextEmitters = new CopyOnWriteArrayList<>();

  void onLifecycleEvent(@ObservesAsync CaseLifecycleEvent event) {
    for (var emitter : lifecycleEmitters) {
      emitter.emit(event);
    }
  }

  void onContextChangedEvent(@ObservesAsync CaseContextChangedEvent event) {
    for (var emitter : contextEmitters) {
      emitter.emit(event);
    }
  }

  public Multi<CaseLifecycleEvent> lifecycleStream() {
    return Multi.createFrom()
        .<CaseLifecycleEvent>emitter(
            emitter -> {
              lifecycleEmitters.add(emitter);
              emitter.onTermination(() -> lifecycleEmitters.remove(emitter));
            },
            io.smallrye.mutiny.subscription.BackPressureStrategy.DROP);
  }

  public Multi<CaseContextChangedEvent> contextChangeStream() {
    return Multi.createFrom()
        .<CaseContextChangedEvent>emitter(
            emitter -> {
              contextEmitters.add(emitter);
              emitter.onTermination(() -> contextEmitters.remove(emitter));
            },
            io.smallrye.mutiny.subscription.BackPressureStrategy.DROP);
  }
}
