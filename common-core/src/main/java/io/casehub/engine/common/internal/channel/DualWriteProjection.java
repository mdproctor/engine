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
package io.casehub.engine.common.internal.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.spi.ExchangeProjectionStrategy;
import io.casehub.worker.api.Exchange;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@DefaultBean
@ApplicationScoped
public class DualWriteProjection implements ExchangeProjectionStrategy {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public Map<String, Object> project(Exchange<?> exchange, ProjectionContext context) {
    if (exchange.body() == null) {
      return Map.of();
    }
    if (exchange.body() instanceof Map<?, ?> bodyMap) {
      @SuppressWarnings("unchecked")
      Map<String, Object> result = (Map<String, Object>) bodyMap;
      return Map.copyOf(result);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> converted = MAPPER.convertValue(exchange.body(), Map.class);
    return converted;
  }

  @Override
  public String id() {
    return "dual-write";
  }
}
