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
package io.casehub.engine.common.internal.context;

import io.casehub.api.context.DataRef;
import io.casehub.api.spi.DataRefResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class DataRefRegistry {

  private final Instance<DataRefResolver> resolvers;

  @Inject
  public DataRefRegistry(Instance<DataRefResolver> resolvers) {
    this.resolvers = resolvers;
  }

  @SuppressWarnings("unchecked")
  public <T> T resolve(DataRef<T> ref) {
    return (T)
        resolvers.stream()
            .filter(r -> r.id().equals(ref.source()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException("No DataRefResolver for source: " + ref.source()))
            .resolve(ref);
  }
}
