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

import io.casehub.api.context.DataRef;
import io.casehub.platform.api.routing.NamedStrategy;

/**
 * Resolves {@link DataRef} references to domain objects.
 *
 * <p>CDI-discovered. Each resolver declares {@link #id()} matching the {@code source} field on
 * DataRef values it handles. No {@code @DefaultBean} — if no resolver exists for a source,
 * resolution fails fast.
 */
public interface DataRefResolver extends NamedStrategy {
  <T> T resolve(DataRef<T> ref);
}
