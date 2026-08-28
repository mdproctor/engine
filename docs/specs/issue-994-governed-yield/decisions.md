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

## D10: JudgmentTarget Type Structure

**Choice:** Flat builder with sealed CallerConfig record
**Alternatives:**
- Minimal core + extension map — maximally extensible but loses type safety for caller-specific fields.
- Sealed JudgmentTarget subtypes — better compile-time safety per caller type, but forces caller-awareness into the BindingTarget dispatch layer, contradicting caller-agnosticism principle.
**Rationale:** Engine handler treats all judgments uniformly — one code path, one JudgmentRequest type. CallerConfig is opaque data for the scheduler, not structure for the engine. Caller-type-specific pre-processing (candidate resolution, title evaluation) moves into the scheduler where it belongs.
**Trade-offs:** CallerConfig access requires cast from sealed base. Less compile-time enforcement of caller-specific field validity than sealed subtypes. Acceptable — the builder convenience methods (.forHuman(), .forLlm()) provide the ergonomic API surface.
**Sources:** HumanTaskTarget.java structure, CaseContextChangedEventHandler.publishHumanTaskSchedule()
**Exploration:** deep-analysis
**Status:** captured

## D11: Evidence Model

**Choice:** Named requirement slots with typed evidence
**Alternatives:**
- Schema-validated evidence (JSON Schema) — maximum flexibility but loses semantic types.
- No structured evidence in v1 — simplest but evidence quality becomes ad-hoc.
**Rationale:** Evidence is the differentiator. Named requirements (name, type, required flag) are declarative and verifier-friendly. EvidenceType enum (DOCUMENT, REFERENCE, REASONING, ATTESTATION) gives semantic meaning. JudgmentResponse carries List<Evidence> matched by name.
**Trade-offs:** Callers must produce named evidence entries — higher bar than free-form. Acceptable for judgment-class decisions.
**Sources:** Epic #994 evidence requirements design, qhorus E5 compliance evidence export
**Exploration:** quick
**Status:** captured

## D12: YAML Syntax

**Choice:** `judgment:` block with `caller:` key replacing `humanTask:`
**Alternatives:**
- `yield:` block — more abstract naming, same structure.
- Keep `humanTask:` as alias — eases migration but two syntaxes coexist permanently.
**Rationale:** Clean break. Pre-release platform — no backward compat needed. `caller.type` selects caller category (human, llm, a2a, any). Human-specific fields nest under `caller:`. Verification and evidence are top-level within the judgment block. Migration is mechanical find-replace.
**Trade-offs:** Every consumer YAML with `humanTask:` blocks must change. Mechanical migration.
**Sources:** Existing HumanTaskTarget YAML mapping in CaseDefinitionYamlMapper
**Exploration:** quick
**Status:** captured

## D13: Blocks Pattern Integration

**Choice:** Yield step in DagPlan — DagNode carries JudgmentTarget
**Alternatives:**
- Pattern-level judgment hooks — opinionated insertion points but less flexible.
- Judgment as WorkerFunction variant — reuses worker pipeline but conflates workers (do) with judgments (decide).
**Rationale:** DagNode already supports contingency sub-plans. Adding JudgmentTarget as an alternative to task on a DagNode is natural. DagDriver pauses the node on yield, resumes on response. Pattern variants declare yield nodes at definition time. No new execution model.
**Trade-offs:** DagDriver needs pause/resume semantics for yield nodes. DagNode type parameter becomes more complex (task OR judgment).
**Depends on:** D1 (JudgmentTarget as single yield mechanism), D10 (flat builder structure)
**Sources:** DagDriver, DagNode, DagPlan, PatternWorkerFunctionHandler, blocks#60 phases
**Exploration:** quick
**Status:** captured

## D14: Qhorus Commitment Mapping

**Choice:** New JUDGMENT commitment type in qhorus speech act taxonomy
**Alternatives:**
- Reuse COMMAND commitment — simpler but loses semantic distinction between task commands and judgment requests.
- Commitment mapping deferred to qhorus — loosest coupling but qhorus must interpret engine events without engine-side semantics.
**Rationale:** Judgment yields ARE deontic commitments with distinct semantics from task commands. JUDGMENT commitment type maps naturally: obligor=caller (must respond), obligee=engine (waits), deadline=expiresIn. Fulfillment=response, violation=timeout/escalation. E7 formal verification validates temporal properties on JUDGMENT commitments specifically.
**Trade-offs:** New commitment type requires qhorus speech act taxonomy update. Acceptable — this is exactly the kind of governance extension qhorus is designed for.
**Depends on:** D2 (JudgmentScheduler SPI)
**Sources:** Qhorus speech act taxonomy, qhorus#411 (judgment commitment type), E7 (#404) formal verification
**Exploration:** quick
**Status:** captured

## D15: Judgment Mode Per Pattern Type

**Choice:** Configurable with sensible defaults per pattern type
**Alternatives:**
- Supervisor IS judgment only — hardcoded, no override. Simple but inflexible.
- Judgment always separate from agent review — two-step every time. Redundant for supervisor.
**Rationale:** Each pattern type has a natural judgment placement. SUPERVISOR: judgment replaces supervisor review (supervisor IS the caller). DEBATE: judgment replaces judge convergence. PIPELINE: judgment after each step. Override via `mode:` field when you need the non-default behavior (e.g., post-review validation after supervisor).
**Trade-offs:** Default must be correct for the common case. Override adds a config field but only surfaces when needed — zero complexity in the default path.
**Depends on:** D13 (pattern integration approach)
**Sources:** SupervisorBuilder.java, DebateBuilder.java (JudgeConvergence), AbstractExecutionDriver five-phase loop
**Exploration:** quick
**Status:** captured

## D16: v1 Caller Scope in Patterns

**Choice:** LLM + A2A callers (both synchronous request/response)
**Alternatives:**
- LLM only — smallest scope but artificially limits when A2A infrastructure exists.
- All callers including human — requires mid-pattern yield (new WorkerFunction lifecycle variant).
**Rationale:** Both LLM and A2A are synchronous within the pattern loop — ChatModel call vs HTTP call. A2AClient already exists. Human callers require the pattern to yield mid-execution and resume later, which is a new execution lifecycle deferred per the governed yield spec.
**Trade-offs:** Human callers in patterns deferred. Acceptable — judgment bindings at the case level already handle human callers.
**Sources:** LlmJudgmentScheduler.java, A2AClient.java, governed yield spec §9 (execution model constraint)
**Exploration:** quick
**Status:** captured

## D17: Judgment Phase Placement

**Choice:** ExecutionModel gains nullable JudgmentPhase as 12th component
**Alternatives:**
- TerminationCondition wrapper — shoehorns judgment semantics into termination (approve/reject != continue/stop).
- ExecutionEventListener — listeners are observation-only (void return), can't influence the loop.
**Rationale:** Judgment is a first-class concern alongside routing, activation, aggregation, and termination. AbstractExecutionDriver.executeIteration() calls it between aggregation and termination. Nullable — patterns without judgment behave exactly as today. The phase returns a JudgmentDecision (Approved/Rejected/Escalated) that influences the loop.
**Trade-offs:** ExecutionModel record gains a 12th component. Backward-compatible via existing constructors passing null.
**Depends on:** D15 (mode per pattern type)
**Sources:** AbstractExecutionDriver.java (five-phase loop), ExecutionModel.java (11-component record)
**Exploration:** quick
**Status:** captured

## D18: Judgment Rejection Behavior

**Choice:** Re-iterate with feedback injected into context
**Alternatives:**
- Fail immediately — simple but no recovery within the pattern.
- Configurable (re-iterate or fail) — adds config field for both behaviors.
**Rationale:** Natural for SUPERVISOR (supervisor says "try again with more detail"). Rejection feedback becomes part of the context for the next iteration. Bounded by existing maxIterations termination — no infinite loops. For DEBATE, rejection means the judge needs another round. For PIPELINE, rejection means the step needs rework.
**Trade-offs:** Rejection doesn't immediately fail — agents get another chance. Waste of compute if the issue is fundamental. Bounded by maxIterations.
**Sources:** OrchestratedDriver.runLoop(), MaxIterationsTermination
**Exploration:** quick
**Status:** captured
