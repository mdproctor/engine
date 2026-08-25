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
package io.casehub.engine.internal.engine;

import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

@ApplicationScoped
public class EngineResetService {

  private static final Logger LOG = Logger.getLogger(EngineResetService.class);

  private final List<Resettable> resettables;

  @Inject
  public EngineResetService(Instance<Resettable> resettables) {
    this.resettables = resettables.stream().toList();
  }

  EngineResetService(List<Resettable> resettables) {
    this.resettables = resettables;
  }

  public void reset() {
    LOG.infof("Engine reset — clearing %d stateful components", resettables.size());
    for (Resettable r : resettables) {
      try {
        r.reset();
      } catch (Exception e) {
        LOG.warnf(e, "Reset failed for %s — continuing", r.getClass().getName());
      }
    }
    LOG.info("Engine reset complete");
  }
}
