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
package io.casehub.engine.common.internal.utils;

import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle;
import io.smallrye.common.vertx.VertxContext;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import java.util.function.Supplier;

public final class ReactiveUtils {

  private ReactiveUtils() {}

  @SuppressWarnings("unchecked")
  public static <T> Uni<T> runOnSafeVertxContext(Vertx vertx, Supplier<Uni<? extends T>> action) {
    return Uni.createFrom()
        .<T>deferred(() -> (Uni<T>) action.get())
        .runSubscriptionOn(
            command -> {
              Context dc = VertxContext.getOrCreateDuplicatedContext(vertx);
              VertxContextSafetyToggle.setContextSafe(dc, true);
              dc.runOnContext(v -> command.run());
            });
  }
}
