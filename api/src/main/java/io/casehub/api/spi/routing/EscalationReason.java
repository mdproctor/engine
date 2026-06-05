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
package io.casehub.api.spi.routing;

/** Why agent routing escalated to human oversight. */
public enum EscalationReason {

  /**
   * All candidates scored 0.0 and at least one was BORDERLINE (score within {@code
   * borderlineMargin} of {@code threshold}). The pool has agents but none are clearly qualified.
   */
  BORDERLINE_STALEMATE,

  /**
   * No QUALIFIED agent is available; only BOOTSTRAP-phase agents could be assigned. Pre-screen
   * fires before scoring — no scoring has occurred. Requires human routing.
   */
  NO_QUALIFIED_AGENT
}
