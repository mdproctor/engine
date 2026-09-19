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
package io.casehub.api.model.stigmergy;

import jakarta.annotation.Nullable;

public record IntegrationPolicy(
    @Nullable Double bootstrapRichness,
    @Nullable Integer integrationDelay,
    @Nullable Double selfDetermination) {

  public static final IntegrationPolicy BALANCED = new IntegrationPolicy(0.7, 3, 0.5);

  public double effectiveBootstrapRichness() {
    return bootstrapRichness != null ? bootstrapRichness : 0.7;
  }

  public int effectiveIntegrationDelay() {
    return integrationDelay != null ? integrationDelay : 3;
  }

  public double effectiveSelfDetermination() {
    return selfDetermination != null ? selfDetermination : 0.5;
  }
}
