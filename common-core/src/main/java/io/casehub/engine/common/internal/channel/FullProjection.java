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
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class FullProjection implements ExchangeProjectionStrategy {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public Map<String, Object> project(Exchange<?> exchange, ProjectionContext context) {
    Map<String, Object> result = new LinkedHashMap<>();

    if (exchange.body() != null) {
      if (exchange.body() instanceof Map<?, ?> bodyMap) {
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) bodyMap;
        result.putAll(typed);
      } else {
        @SuppressWarnings("unchecked")
        Map<String, Object> converted = MAPPER.convertValue(exchange.body(), Map.class);
        result.putAll(converted);
      }
    }

    if (!exchange.headers().isEmpty()) {
      result.put(
          "_exchange." + context.bindingName() + ".headers",
          new LinkedHashMap<>(exchange.headers()));
    }

    return result;
  }

  @Override
  public String id() {
    return "full";
  }
}
