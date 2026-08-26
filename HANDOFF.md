# HANDOFF — engine#994 Governed Yield

**Branch:** `issue-994-governed-yield`
**Date:** 2026-08-26

## Last Session

Designed and implemented the engine foundation for governed yield — a caller-agnostic judgment infrastructure that replaces HumanTaskTarget with a unified JudgmentTarget mechanism supporting any caller type (human, LLM, A2A), post-response verification, evidence requirements, trust-weighted routing, and ledger provenance.

### Design Phase

Full architecture spec with 14 design decisions, light decision review (16 findings, 3 HIGH addressed), and light spec review (14 findings, key issues fixed). Design covers engine, blocks, and qhorus layers.

- Spec: `docs/specs/issue-994-governed-yield/2026-08-26-governed-yield-design.md`
- Decisions: `docs/specs/issue-994-governed-yield/decisions.md`
- Plan: `docs/plans/2026-08-26-governed-yield.md`

### Implementation — Batches 1-2 Complete (Engine Foundation)

**Batch 1 — Engine Foundation Types:**
- `JudgmentTarget` with builder pattern and `CallerConfig` sealed hierarchy (Human, Llm, A2A, Any)
- `EvidenceRequirement`, `EvidenceType`, `VerificationMode`, `Evidence`, `CallerIdentity`, `JudgmentResponse`
- `JudgmentScheduler` SPI with `JudgmentRequest` + sealed `JudgmentPayload` (BindingPayload | GatePayload)
- `PendingJudgment` state record, `NoOpJudgmentScheduler` (`@DefaultBean`)
- `JudgmentVerifier` SPI with `VerificationResult` sealed (Accepted | InsufficientEvidence | TrustTooLow | Rejected)
- `EvidencePresenceVerifier` (id="evidence-presence"), `NoOpJudgmentVerifier` (`@DefaultBean`)
- YAML `judgment:` block parsing in `CaseDefinitionYamlMapper`
- `BindingTarget` sealed permits updated, `Binding.judgment()` builder method

**Batch 2 — Handler Wiring + Ledger Events:**
- 7 judgment event types in `CaseHubEventType` (YIELDED, RESPONDED, VERIFIED, ESCALATED, REJECTED, CANCELLED, EXPIRED)
- `JudgmentEscalator` SPI with `EscalationDecision` sealed (ReYield | Escalate | Fault)
- `DefaultJudgmentEscalator` (`@DefaultBean`, id="default")
- `JudgmentTarget` dispatch wired into `CaseContextChangedEventHandler.publishByTarget()` switch
- `JudgmentResponseHandler` consuming `JUDGMENT_RESPONSE` event bus with verification pipeline
- `EngineStrategyResolver` updated with `Instance<JudgmentVerifier>` and `Instance<JudgmentEscalator>`

**Batch 3 — Qhorus:**
- `JUDGMENT` message type added to qhorus `MessageType` enum with commissive semantics

### Deferred (10 tasks)

| Task | Blocker |
|------|---------|
| qhorus#412 E4 trust routing | Blocked by qhorus E4 (#401) + ledger#200 |
| blocks#171 LLM JudgmentScheduler | engine-api SNAPSHOT cannot install (pre-existing worker-api mismatch) |
| blocks#172 verification strategies | Same as #171 |
| blocks#173 yield-aware patterns | Same + mid-pattern yield infrastructure (future) |
| engine#1000 DagNode judgment | DagNode.task non-null constraint (future) |
| qhorus#413 E5 compliance evidence | Blocked by qhorus E5 (#402) + ledger#201 |
| qhorus#414 E7 formal verification | Blocked by qhorus E7 (#404) |
| Delete HumanTaskTarget + old SPIs | engine-api compilation fix needed |
| Update CloudEvent module | Same |
| Update consumer examples | Same |

## Immediate Next Step

Fix the pre-existing engine-api compilation errors (worker-api SNAPSHOT mismatch — `Capability.inputProjection()`, `Worker.capabilities()` missing). Once the api module compiles and installs cleanly, the blocks and migration tasks unblock.
