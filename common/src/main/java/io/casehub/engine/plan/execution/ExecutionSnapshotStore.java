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
package io.casehub.engine.plan.execution;

import io.casehub.engine.plan.snapshot.DagPlanSnapshot;
import io.casehub.engine.plan.snapshot.DecompositionSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionSnapshotStore {

  void storeDecomposition(UUID caseId, DecompositionSnapshot snapshot);

  Optional<DecompositionSnapshot> getDecomposition(UUID caseId, String tenancyId);

  void storeDagPlan(UUID caseId, DagPlanSnapshot snapshot);

  Optional<DagPlanSnapshot> getDagPlan(UUID caseId, String tenancyId);

  void storeDagResult(UUID caseId, DagResultSnapshot snapshot);

  Optional<DagResultSnapshot> getDagResult(UUID caseId, String tenancyId);

  void evict(UUID caseId);
}
