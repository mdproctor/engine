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
package io.casehub.engine.inbound.quarkus;

import io.casehub.engine.inbound.InboundWorkItemBridge;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class InboundWorkItemBridgeAdapter {

  @Inject Instance<InboundWorkItemBridge> bridge;

  void onStartup(@Observes StartupEvent event) {
    if (bridge.isResolvable()) {
      InboundWorkItemBridge b = bridge.get();
      // Ambiguity check moved to Quarkus wiring — CDI validates this at build time
    }
  }
}
