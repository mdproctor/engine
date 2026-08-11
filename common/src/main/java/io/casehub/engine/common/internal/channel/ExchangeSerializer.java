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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.ContextBridge;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.worker.api.Exchange;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class ExchangeSerializer {

  private final BridgeResolver bridgeResolver;
  private final ObjectMapper objectMapper;

  @Inject
  public ExchangeSerializer(BridgeResolver bridgeResolver, ObjectMapper objectMapper) {
    this.bridgeResolver = bridgeResolver;
    this.objectMapper = objectMapper;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> toMetadata(Exchange<?> exchange, Class<?> bodyType) {
    Map<String, Object> metadata = new LinkedHashMap<>();

    if (exchange.body() != null) {
      ContextBridge<?> bridge = bridgeResolver.resolveByType(bodyType);
      JsonNode serializedBody = bridgeResolver.serialise(bridge, exchange.body());
      metadata.put("exchangeBody", objectMapper.convertValue(serializedBody, Object.class));
    }

    if (!exchange.headers().isEmpty()) {
      metadata.put("exchangeHeaders", new LinkedHashMap<>(exchange.headers()));
    }

    metadata.put("exchangeBodyType", bodyType.getName());
    return metadata;
  }

  @SuppressWarnings("unchecked")
  public <T> Exchange<T> fromMetadata(Map<String, Object> metadata, Class<T> bodyType) {
    T body = null;
    if (metadata.containsKey("exchangeBody")) {
      ContextBridge<T> bridge = (ContextBridge<T>) bridgeResolver.resolveByType(bodyType);
      JsonNode bodyNode = objectMapper.valueToTree(metadata.get("exchangeBody"));
      body = bridgeResolver.deserialise(bridge, bodyNode);
    }

    Map<String, Object> headers = Map.of();
    Object rawHeaders = metadata.get("exchangeHeaders");
    if (rawHeaders instanceof Map<?, ?> headerMap) {
      headers = (Map<String, Object>) headerMap;
    }

    return new Exchange<>(body, headers, Map.of());
  }
}
