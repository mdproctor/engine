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
package io.casehub.engine.internal.worker;

import io.casehub.api.model.WorkResult;
import io.casehub.api.spi.WorkerStatusListener;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/** Default no-op WorkerStatusListener. Silently ignores all lifecycle events. */
@DefaultBean
@ApplicationScoped
public class NoOpWorkerStatusListener implements WorkerStatusListener {

  @Override
  public void onWorkerStarted(String workerId, Map<String, String> sessionMeta) {
    // intentional no-op
  }

  @Override
  public void onWorkerCompleted(String workerId, WorkResult result) {
    // intentional no-op
  }

  @Override
  public void onWorkerStalled(String workerId) {
    // intentional no-op
  }
}
