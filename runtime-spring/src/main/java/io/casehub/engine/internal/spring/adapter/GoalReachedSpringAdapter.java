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
package io.casehub.engine.internal.spring.adapter;

import io.casehub.engine.common.internal.event.GoalReachedEvent;
import io.casehub.engine.internal.engine.handler.GoalReachedEventHandler;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class GoalReachedSpringAdapter {

  private final GoalReachedEventHandler core;

  public GoalReachedSpringAdapter(GoalReachedEventHandler core) {
    this.core = core;
  }

  @EventListener
  public void handle(GoalReachedEvent event) {
    core.handle(event);
  }
}
