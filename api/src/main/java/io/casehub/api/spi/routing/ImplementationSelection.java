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
package io.casehub.api.spi.routing;

import java.util.List;

/**
 * Result of {@link ImplementationRoutingStrategy#select}. A sealed type with three outcomes:
 *
 * <ul>
 *   <li>{@link Selected} — one or more specific bindings were chosen
 *   <li>{@link RunAll} — all implementations run (current default behaviour)
 *   <li>{@link RunNone} — all candidates are inappropriate; skip this capability
 * </ul>
 *
 * <p>Callers must switch exhaustively on the sealed type. Refs casehubio/engine#476.
 */
public sealed interface ImplementationSelection
    permits ImplementationSelection.Selected,
        ImplementationSelection.RunAll,
        ImplementationSelection.RunNone {

  record Selected(List<String> bindingNames) implements ImplementationSelection {
    public Selected(List<String> bindingNames) {
      if (bindingNames.isEmpty())
        throw new IllegalArgumentException("Use RunNone for empty selection");
      this.bindingNames = List.copyOf(bindingNames);
    }
  }

  record RunAll() implements ImplementationSelection {}

  record RunNone() implements ImplementationSelection {}
}
