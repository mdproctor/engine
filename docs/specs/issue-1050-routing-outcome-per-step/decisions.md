## D1: SPI shape for per-step outcome observation

**Choice:** New `StepOutcomeObserver` SPI in `api/spi/` — symmetric with `CaseOutcomeObserver`
**Alternatives:**
- Enrich `RoutingOutcomeRecorder.record()` with caseType — blurs routing feedback vs outcome observation, leaks engine internals (CaseDefinitionRegistry) into consumers
- Default method on `CaseOutcomeObserver` — name stops being accurate, forces single-class dual-purpose implementation
**Rationale:** Two distinct observation concerns at different granularity need distinct SPIs. The context snapshot at step execution time is the key value — consumers extract domain-specific features from it without engine internals. Recording shape (what CBR case type, what features) is domain logic owned by the consumer, not the engine.
**Trade-offs:** One more SPI type to maintain; one more `Instance<>` injection in WorkflowExecutionCompletedHandler (already has ~15)
**Sources:** CaseOutcomeObserver (api/spi/), RoutingOutcomeRecorder (api/spi/routing/), WorkflowExecutionCompletedHandler (runtime), CbrCaseRetainObserver (runtime/internal/memory/), GE-20260706-56a75c
**Exploration:** quick
**Status:** captured

## D2: StepOutcomeEvent field set

**Choice:** Minimal primitives + Map: `caseId` (UUID), `tenancyId` (String), `caseType` (String), `bindingName` (String), `capabilityName` (@Nullable String), `workerName` (String), `outcome` (RoutingOutcome), `contextSnapshot` (Map<String, Object>), `executionDuration` (@Nullable Duration)
**Alternatives:**
- Include CaseDefinition reference — couples event to engine domain model, consumer already knows its own definition
- Include scope (Path) — engine doesn't know CBR retrieval scope at step time; consumer derives it from contextSnapshot
**Rationale:** Event should be a bag of primitives and a Map, same shape as CaseOutcomeEvent. Consumer resolves what it needs (CbrConfig via its own registry, scope from context data). contextSnapshot is the working layer at step execution time — the key value for per-step feature extraction.
**Trade-offs:** Consumer must look up CaseDefinition if it needs CbrConfig. Acceptable — consumer registered the definition and already injects the registry.
**Sources:** CaseOutcomeEvent (api/spi/), CbrCaseRetainObserver.doRetain() (runtime/internal/memory/), CbrCaseMemoryStore.store() signature
**Exploration:** quick
**Status:** captured

## D3: Firing point and context snapshot timing

**Choice:** Fire on both success and failure paths. Success path uses `contextBefore` (pre-output-application snapshot — the conditions under which the step executed). Failure path uses current snapshot (no output was applied, so the snapshot captures what the step saw when it failed).
**Alternatives:**
- Fire on both paths using post-output context — loses "conditions at execution time" semantic; comparison in plan adaptation would use a dampened signal
- Fire on success only — loses failure evidence; "under what conditions does this step fail?" is equally valuable for routing
**Rationale:** The feature vector must describe the conditions under which the decision was made, not the world after execution. CBR queries match on input conditions ("high volatility at 2am"), not output state. Plan adaptation (FsiPlanAdapter boost/substitute) compares current conditions against stored conditions — both sides must be input conditions for the comparison to be meaningful.
**Trade-offs:** On the success path, the snapshot is taken before output application — if the consumer also wants the output, they'd need to observe it separately (but that's what CaseOutcomeObserver's caseFileSnapshot already provides at case close).
**Sources:** WorkflowExecutionCompletedHandler.onWorkflowExecutionCompletedHandler() lines 204-226 (contextBefore), handleSemanticFailure() lines 463-468
**Exploration:** quick
**Status:** captured
