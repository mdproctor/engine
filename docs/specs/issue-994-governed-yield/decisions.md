# Governed Yield — Design Decisions

## D1: Unification Strategy

**Choice:** Replace HumanTaskTarget entirely with JudgmentTarget
**Alternatives:**
- Generalize alongside — keep HumanTaskTarget for backward compat, JudgmentTarget as new permit. Avoids consumer migration but two yield mechanisms coexist permanently.
- Evolve HumanTaskTarget in place — rename and widen. Evolutionary but semantic constraint of "HumanTask" name muddies caller-agnostic intent.
**Rationale:** Pre-release platform — breaking changes cost nothing. Single mechanism eliminates redundancy between HumanTaskTarget and ActionGate WorkItem creation. Human-specific fields (candidateGroups, title, outcomes) become a caller-config block on JudgmentTarget.
**Trade-offs:** All consumers using HumanTaskTarget must migrate. All YAML `humanTask:` blocks must change.
**Sources:** BindingTarget sealed hierarchy (engine-api), HumanTaskTarget.java, ActionRiskClassifier gate in WorkflowExecutionCompletedHandler
**Exploration:** quick
**Status:** captured

## D2: Scheduler SPI

**Choice:** Single JudgmentScheduler replaces both HumanTaskScheduler and ActionGateScheduler
**Alternatives:**
- Split by trigger timing — pre-dispatch vs post-dispatch as separate SPIs. Different semantics but massive structural overlap.
- Layered — JudgmentScheduler + thin ActionGateAdapter. Clean but still two interfaces.
**Rationale:** HumanTaskScheduler and ActionGateScheduler are structurally identical (`schedule(Request)`). One SPI, one CloudEvent implementation, one work-adapter implementation. Differences handled by request payload, not interface.
**Trade-offs:** Gate requests and binding-declared yields share the same scheduling path — must ensure gate-specific semantics (deferred output, approval/rejection) are preserved.
**Sources:** HumanTaskScheduler.java, ActionGateScheduler.java, CloudEventHumanTaskScheduler
**Exploration:** quick
**Status:** captured

## D3: Verification Timing

**Choice:** Synchronous verification now with VerificationMode enum for future async evolution
**Alternatives:**
- Async audit — accept immediately, verify in background. Faster but temporarily inconsistent state.
- Configurable per-binding — both modes available now. Adds complexity without a concrete async use case.
**Rationale:** Strong guarantee: no unverified judgment ever advances the case. VerificationMode enum on JudgmentTarget starts with SYNCHRONOUS only. JudgmentVerifier SPI is pure (validate) — the timing is a handler concern. Adding ASYNC later is a new enum value + new handler code path, zero SPI change.
**Trade-offs:** Adds latency on every judgment response. Acceptable for judgment-class decisions.
**Sources:** Existing ActionRiskClassifier synchronous gate pattern in WorkflowExecutionCompletedHandler
**Exploration:** quick
**Status:** captured

## D4: Response Model

**Choice:** Typed resolution + evidence (JudgmentResponse with decision, evidence, callerIdentity, responseTime)
**Alternatives:**
- Outcome enum + payload — simpler but evidence requirements become ad-hoc payload conventions.
- Sealed response (Decided/Deferred/Abstained) — models full response space but adds complexity without concrete Deferred/Abstained use cases yet.
**Rationale:** Evidence is a first-class concept in governed judgment. Verifier validates both decision and evidence. Clean separation of what-was-decided from why. Typed resolution via ContextBridge (existing pattern from HumanTaskTarget.resolutionType).
**Trade-offs:** New Evidence type to design. Callers must produce structured evidence — higher bar than today's free-form WorkItem resolution.
**Sources:** HumanTaskTarget.resolutionType, ContextBridge protocol (engine#203)
**Exploration:** quick
**Status:** captured

## D5: ActionRiskClassifier Gate Integration

**Choice:** Classifier stays as classification SPI, produces JudgmentRequest when GateRequired
**Alternatives:**
- Merge classifier into JudgmentVerifier — conflates "should we do this" with "was this done right".
- Classifier creates inline JudgmentTarget — elegant but runtime binding mutation adds complexity.
**Rationale:** Clean layering: classifier decides IF a gate is needed; JudgmentScheduler decides WHERE it goes. ActionRiskClassifier interface unchanged. GateRequired fields map naturally to JudgmentRequest fields.
**Trade-offs:** Two classification steps on the gated path (risk classification, then judgment verification). Acceptable — they answer different questions.
**Sources:** ActionRiskClassifier.java, RiskDecision.java, @RiskClassifier qualifier pattern (GE-20260607-3b6711)
**Exploration:** quick
**Status:** captured

## D6: Escalation Model

**Choice:** JudgmentEscalator separate from RecoveryCoordinator
**Alternatives:**
- Integrate into recovery levels — maps verification failure to RecoveryLevel. But judgment failures are semantically different from worker crashes.
- Unified EscalationCoordinator — most general but touches the entire recovery protocol.
**Rationale:** Different failure domains: RecoveryCoordinator handles worker execution failures (crash, timeout, decline); JudgmentEscalator handles judgment response failures (insufficient evidence, trust too low, rejected). They share trust/routing infrastructure but have independent escalation chains.
**Trade-offs:** Two escalation SPIs. Consumer must understand which fires when. Clear separation by failure domain makes this manageable.
**Sources:** RecoveryCoordinator, DefaultRecoveryCoordinator, multi-level recovery protocol
**Exploration:** quick
**Status:** captured

## D7: Audit Trail

**Choice:** EventLog with judgment event types (JUDGMENT_YIELDED, JUDGMENT_RESPONDED, JUDGMENT_VERIFIED, JUDGMENT_ESCALATED)
**Alternatives:**
- Separate judgment ledger stream — richer schema but fragments the audit trail.
- Dual-write EventLog + CaseLedgerEntry — hash-chained integrity but double write cost.
**Rationale:** Consistent with existing patterns — all case lifecycle events go through EventLog. Metadata carries evidence requirements, caller identity, verification result, trust score. E5 queries EventLog by type. E7 validates temporal properties across the event sequence.
**Trade-offs:** Metadata is unstructured JSON. Formal verification (E7) must parse metadata rather than query typed columns. Acceptable — EventLog metadata is the established pattern.
**Sources:** CaseHubEventType enum, existing WORKER_EXECUTION_* events, qhorus E5 (#402), E7 (#404)
**Exploration:** quick
**Status:** captured

## D8: Trust Model

**Choice:** Trust threshold on JudgmentTarget
**Alternatives:**
- Trust as a verification dimension — flexible but filtering happens late (after caller has responded).
- Trust-tiered caller pools — structured but hardcoded tier boundaries.
**Rationale:** Simple, declarative, per-binding. Scheduler filters callers below threshold using existing TrustSignalProvider scores. Verifier can factor trust score as additional context. Qhorus E4 provides scoring; engine consumes via composable signal architecture.
**Trade-offs:** Binary threshold (above/below). Nuanced trust-quality trade-offs (low-trust caller with excellent evidence) handled by verifier, not threshold.
**Sources:** TrustSignalProvider, ComposableAgentRoutingStrategy, qhorus E4 (#401)
**Exploration:** quick
**Status:** captured

## D9: Scope

**Choice:** Full architecture design, full implementation across all batches and repos
**Alternatives:**
- Engine-only design and implementation — faster but risks SPI boundaries that don't fit blocks/qhorus.
- Full design, engine-only implementation — ensures boundaries but defers blocks/qhorus to later branches.
**Rationale:** This is an architectural vision spanning three repos. SPI boundaries must be agreed before code is written. Full implementation ensures the design is validated end-to-end.
**Trade-offs:** Large scope — 5 batches across engine, blocks, qhorus. Mitigated by the slot setup which has all three repos.
**Sources:** Epic #994, child issues across engine (#995-#1000), blocks (#170-#173), qhorus (#410-#414)
**Exploration:** quick
**Status:** captured
