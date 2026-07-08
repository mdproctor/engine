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
package io.casehub.work.engine.recovery;

import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.work.api.WorkItemRef;
import io.casehub.work.api.spi.WorkItemCreator;
import io.casehub.work.engine.PlanItemCallerRef;
import io.casehub.work.engine.PlanItemCompletionApplier;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Catches up WorkItems that completed while the JVM was down — the "offline-completion" scenario in
 * casehubio/engine#398.
 *
 * <p>Runs at {@link Priority} 25 — after Quartz recovery at 20. For each DELEGATED PlanItem in the
 * store, checks the corresponding WorkItem status; if terminal, applies the transition and fires
 * CONTEXT_CHANGED.
 *
 * <p>Idempotent: if a PlanItem has already been transitioned by a concurrent event (e.g. the
 * WorkItemLifecycleAdapter received a late event), {@link PlanItemCompletionApplier#apply} detects
 * the terminal state and skips without error.
 */
@ApplicationScoped
public class HumanTaskRecoveryService {

  private static final Logger LOG = Logger.getLogger(HumanTaskRecoveryService.class);

  @Inject PlanItemStore planItemStore;
  @Inject WorkItemCreator workItemCreator;
  @Inject PlanItemCompletionApplier applier;

  void onStart(@Observes @Priority(25) StartupEvent ev) {
    List<PlanItemRecord> delegated = planItemStore.findAllDelegated();
    if (delegated.isEmpty()) {
      LOG.debug("HumanTaskRecoveryService: no DELEGATED PlanItems found — nothing to recover");
      return;
    }
    LOG.infof(
        "HumanTaskRecoveryService: scanning %d DELEGATED PlanItem(s) for offline completions",
        delegated.size());
    int recovered = 0;
    for (PlanItemRecord r : delegated) {
      if (tryRecover(r)) recovered++;
    }
    LOG.infof(
        "HumanTaskRecoveryService: %d PlanItem(s) recovered out of %d scanned",
        recovered, delegated.size());
  }

  private boolean tryRecover(PlanItemRecord r) {
    String callerRef = PlanItemCallerRef.encode(r.caseId(), r.planItemId());
    Optional<WorkItemRef> refOpt = workItemCreator.findByCallerRef(callerRef);

    if (refOpt.isEmpty()) {
      LOG.debugf(
          "No WorkItem found for callerRef=%s — WorkItem may have been cleaned up; skipping",
          callerRef);
      return false;
    }

    WorkItemRef ref = refOpt.get();
    if (!ref.status().isTerminal()) {
      LOG.debugf(
          "WorkItem %s for callerRef=%s is still in-flight (status=%s) — skipping",
          ref.id(), callerRef, ref.status());
      return false;
    }

    LOG.infof(
        "Recovering PlanItem %s in case %s — WorkItem %s was %s during downtime",
        r.planItemId(), r.caseId(), ref.id(), ref.status());
    applier.apply(r.caseId(), r.planItemId(), ref.status(), ref);
    return true;
  }
}
