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
package io.casehub.engine.common.internal.event;

import io.casehub.engine.common.internal.model.CaseInstance;
import java.util.Objects;

/**
 * Event fired when a CaseHub instance run is started. Contains the initial StateContext created by
 * the Reactor.
 */
public record CaseStartedEvent(CaseInstance instance) {

  public CaseStartedEvent(CaseInstance instance) {
    this.instance = Objects.requireNonNull(instance, "instance cannot be null");
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    CaseStartedEvent that = (CaseStartedEvent) o;
    return Objects.equals(instance, that.instance);
  }

  @Override
  public String toString() {
    return "CaseStartedEvent{" + "uuid=" + instance.getUuid() + '}';
  }
}
