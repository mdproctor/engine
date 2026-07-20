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
package io.casehub.api.engine;

import io.casehub.api.model.Binding;
import java.util.List;

/**
 * SPI for controlling which eligible bindings are selected for execution.
 *
 * <p>Returns {@code Uni<List<Binding>>} to allow non-blocking I/O during selection (e.g. EventLog
 * queries, LLM scoring). See casehubio/engine#76.
 *
 * <p>The default implementation ({@link io.casehub.engine.internal.engine.ChoreographyLoopControl})
 * wraps with {@code Uni.createFrom().item(eligible)} — no behaviour change.
 */
public interface LoopControl {

  /**
   * Select the bindings to fire from the set of bindings whose trigger conditions have already been
   * evaluated and matched.
   *
   * @param context case identity, definition, and current case state — enables implementations to
   *     look up plan models without requiring access to internal engine structures
   * @param eligible bindings whose trigger conditions matched — may be empty, never null
   * @return the subset to fire; may be empty, may be the full list, must not be null
   */
  List<Binding> select(PlanExecutionContext context, List<Binding> eligible);
}
