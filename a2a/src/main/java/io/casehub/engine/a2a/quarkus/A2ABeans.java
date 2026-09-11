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
package io.casehub.engine.a2a.quarkus;

import io.casehub.engine.a2a.A2ACapabilityHealth;
import io.casehub.engine.a2a.A2AClientRegistry;
import io.casehub.engine.a2a.A2AEndpointRegistry;
import io.casehub.engine.a2a.A2AWorkerFunctionHandler;
import io.casehub.engine.a2a.A2AWorkerFunctionProvider;
import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.util.concurrent.ExecutorService;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class A2ABeans {

  @Produces
  @ApplicationScoped
  A2AEndpointRegistry a2aEndpointRegistry() {
    return new A2AEndpointRegistry();
  }

  @Produces
  @ApplicationScoped
  A2AClientRegistry a2aClientRegistry() {
    return new A2AClientRegistry();
  }

  @Produces
  @ApplicationScoped
  A2AWorkerFunctionProvider a2aWorkerFunctionProvider(A2AEndpointRegistry endpointRegistry) {
    return new A2AWorkerFunctionProvider(endpointRegistry);
  }

  @Produces
  @ApplicationScoped
  A2ACapabilityHealth a2aCapabilityHealth(
      A2AEndpointRegistry endpointRegistry, A2AClientRegistry clientRegistry) {
    return new A2ACapabilityHealth(endpointRegistry, clientRegistry);
  }

  @Produces
  @ApplicationScoped
  A2AWorkerFunctionHandler a2aWorkerFunctionHandler(
      A2AClientRegistry clientRegistry,
      @VirtualThreads ExecutorService virtualThreads,
      @ConfigProperty(name = "casehub.a2a.max-artifacts", defaultValue = "100") int maxArtifacts,
      @ConfigProperty(name = "casehub.a2a.max-artifact-bytes", defaultValue = "10485760")
          long maxArtifactBytes) {
    return new A2AWorkerFunctionHandler(
        clientRegistry, virtualThreads, maxArtifacts, maxArtifactBytes);
  }
}
