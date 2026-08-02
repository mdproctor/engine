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
package io.casehub.engine.a2a;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class A2AClientRegistry {

  private final ConcurrentHashMap<String, A2AClient> clients = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, A2AAuthConfig> authConfigs = new ConcurrentHashMap<>();

  public A2AClient getOrCreate(final String endpoint, final A2AAuthConfig auth) {
    final String key = normalizeEndpoint(endpoint);
    final A2AAuthConfig existing = authConfigs.putIfAbsent(key, auth);
    if (existing != null && !existing.equals(auth)) {
      throw new IllegalArgumentException(
          "A2A endpoint auth conflict for "
              + key
              + ": existing="
              + existing.type()
              + ", new="
              + auth.type());
    }
    return clients.computeIfAbsent(key, k -> new A2AClient(k, auth));
  }

  public void evict(final String endpoint) {
    final String key = normalizeEndpoint(endpoint);
    final A2AClient removed = clients.remove(key);
    authConfigs.remove(key);
    if (removed != null) {
      removed.close();
    }
  }

  void shutdown(@Observes final ShutdownEvent event) {
    clients.values().forEach(A2AClient::close);
    clients.clear();
    authConfigs.clear();
  }

  private String normalizeEndpoint(final String endpoint) {
    return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
  }
}
