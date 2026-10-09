# Decisions — #1205 Concurrent Registry Re-registration

## D1: Registry atomicity strategy

**Choice:** Atomic snapshot swap — volatile reference to immutable Map
**Alternatives:**
- Eliminate lazy re-registration path — simpler but removes self-healing for stale CDI bean instances
- ReadWriteLock — correct but adds lock contention on every hot-path lookup
**Rationale:** ConcurrentHashMap provides per-entry atomicity but not batch atomicity. A volatile reference to an immutable snapshot guarantees readers see either the old (empty) or fully populated state, never partial. The lazy re-registration path stays as a safety net.
**Trade-offs:** Slight memory overhead from immutable copy; lazy path is rarely exercised so the complexity is low-risk.
**Sources:** DefaultCaseDefinitionRegistry.java:181-185 (getCaseDefinition), issue #424 (prior partial fix)
**Exploration:** quick
**Status:** captured

## D2: Coalescing strategy for CaseEvaluationSerializer

**Choice:** Re-read latest context at evaluation time (keep single-slot coalescing)
**Alternatives:**
- Queue all events — guarantees no event lost but may re-evaluate unnecessarily
- Merge metadata + re-read context — more complex, same outcome as chosen approach + D3
**Rationale:** Context changes are committed to the case instance before the event fires. Re-reading at evaluation time captures all prior mutations regardless of how many events were coalesced. The single-slot design preserves existing performance characteristics — no unbounded queue growth.
**Trade-offs:** Two near-simultaneous events that require different changedLayer values will only evaluate with the latest layer. This is acceptable because the WORKING layer evaluation in rules() covers all layers.
**Sources:** CaseEvaluationSerializer.java:35-55 (submit), CaseContextChangedEventHandler.java:239-257 (handle)
**Exploration:** quick
**Status:** captured

## D3: Reset coordination strategy

**Choice:** Drain with timeout — wait for in-flight evaluations, reject new submissions, force-clear after 5s
**Alternatives:**
- Immediate cancel — fastest but risks partial state from interrupted mid-write evaluations
- Drain indefinitely — safest but risks hanging if an evaluation is stuck
**Rationale:** The drain-with-timeout pattern balances safety (no stale overlaps) against liveness (no indefinite hangs). A `closed` flag prevents new submissions from racing with the drain. The 5s timeout is generous for evaluation cycles that typically complete in milliseconds.
**Trade-offs:** Submissions during the drain window are silently dropped. Callers (typically test lifecycle) don't expect to submit during reset, so this is acceptable.
**Sources:** CaseEvaluationSerializer.java:62-64 (reset), Resettable.java (contract)
**Exploration:** quick
**Status:** captured

## D4: Signal ID preservation during coalescing

**Choice:** Accumulate signalIds in the CaseGate — a Set<UUID> collects IDs from all coalesced submissions
**Alternatives:**
- Settle eagerly before coalescing — simpler but changes settlement semantics from "dispatched" to "acknowledged"
- Ignore — settlement is advisory, system self-corrects on next cycle
**Rationale:** SignalSettlementTracker expects markFullyDispatched() after the evaluation completes. Accumulating IDs preserves the existing contract. After the coalesced evaluation runs, all accumulated IDs are marked.
**Trade-offs:** Slight complexity in the CaseGate — a Set<UUID> instead of nothing. Minimal overhead.
**Sources:** CaseContextChangedEventHandler.java:303-305 (settlementTracker), SignalSettlementTracker
**Depends on:** D2 (coalescing strategy determines where signalIds flow)
**Exploration:** quick
**Status:** captured
