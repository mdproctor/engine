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
package io.casehub.engine.common.spi;

/**
 * SPI for scheduling action gate work items from engine bindings.
 *
 * <p>Symmetric with {@link HumanTaskScheduler}. Discovered via {@code
 * Instance<ActionGateScheduler>} in the engine runtime — when no implementation is on the
 * classpath, action gates are silently skipped.
 */
public interface ActionGateScheduler {

  void schedule(ActionGateScheduleRequest request);
}
