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

/**
 * Trust maturity phases for routing policy configuration. Determines which phases trigger
 * evidential verification at attestation time.
 *
 * <p>Distinct from {@code TrustCandidateClassifier.Phase} which is a routing-time classification.
 * This enum is policy-level vocabulary — it configures <em>when</em> evidential checking runs, not
 * <em>how</em> candidates are classified.
 *
 * <p>Refs casehubio/engine#711, devtown#141.
 */
public enum TrustPhase {
  BOOTSTRAP,
  QUALIFIED,
  BORDERLINE,
  BELOW_THRESHOLD,
  QUALITY_FAILED
}
