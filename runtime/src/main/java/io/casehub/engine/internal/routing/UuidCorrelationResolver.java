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
package io.casehub.engine.internal.routing;

import io.casehub.api.spi.CaseCorrelationResolver;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

/**
 * Default correlation resolver — parses the correlation value as a UUID directly.
 *
 * <p>Fails with {@link IllegalArgumentException} if the value is null or not a valid UUID string.
 */
@DefaultBean
@ApplicationScoped
public class UuidCorrelationResolver implements CaseCorrelationResolver {

  @Override
  public String id() {
    return "uuid";
  }

  @Override
  public Uni<UUID> resolve(String correlationValue, String tenancyId) {
    if (correlationValue == null) {
      return Uni.createFrom()
          .failure(new IllegalArgumentException("Correlation value must not be null"));
    }
    try {
      return Uni.createFrom().item(UUID.fromString(correlationValue.trim()));
    } catch (IllegalArgumentException e) {
      return Uni.createFrom()
          .failure(
              new IllegalArgumentException(
                  "Correlation value is not a valid UUID: " + correlationValue, e));
    }
  }
}
