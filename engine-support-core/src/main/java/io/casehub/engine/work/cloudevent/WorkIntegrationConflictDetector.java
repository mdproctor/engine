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
package io.casehub.engine.work.cloudevent;

import io.casehub.engine.common.spi.HumanTaskScheduler;
import java.util.List;

public class WorkIntegrationConflictDetector {

  private final List<HumanTaskScheduler> humanTaskSchedulers;

  public WorkIntegrationConflictDetector(List<HumanTaskScheduler> humanTaskSchedulers) {
    this.humanTaskSchedulers = humanTaskSchedulers;
  }

  public void check() {
    long count = humanTaskSchedulers.size();
    if (count > 1) {
      throw new IllegalStateException(
          "Multiple HumanTaskScheduler implementations detected ("
              + count
              + "). casehub-work-engine-adapter and casehub-engine-work-cloudevent "
              + "are mutually exclusive — remove one from the classpath.");
    }
  }
}
