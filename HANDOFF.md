# HANDOFF — engine#994 Governed Yield

**Branch:** `issue-994-governed-yield`
**Date:** 2026-08-28

## Last Session

Implemented blocks#173 yield-aware pattern variants. Added JudgmentPhase as 12th component on ExecutionModel, integrated into AbstractExecutionDriver's five-phase loop (between aggregation and termination). Rejection re-iterates with feedback. LlmJudgmentPhase calls ChatModel inline. PatternJudgmentConfig parsed from YAML `judgment:` blocks. AbstractPatternBuilder gains `.judgment()` for Java DSL. All queue items complete — epic done.

Prior sessions: designed spec (14 decisions), implemented all 6 engine batches, completed HumanTaskTarget → JudgmentTarget migration, JudgmentResponseHandler end-to-end, DagNode judgment (engine#1000), blocks#171 LlmJudgmentScheduler, blocks#172 verification strategies, qhorus#412-414 governance integration.

- Spec: `docs/specs/issue-994-governed-yield/2026-08-26-governed-yield-design.md`
- blocks#173 spec: `docs/specs/issue-994-governed-yield/2026-08-28-yield-aware-patterns-design.md`
- Plan: `docs/plans/2026-08-28-yield-aware-patterns.md`

## What's Done

**Engine (complete):**
- JudgmentTarget + CallerConfig sealed hierarchy
- JudgmentScheduler SPI + JudgmentRequest/JudgmentPayload
- JudgmentVerifier + EvidencePresenceVerifier + NoOpJudgmentVerifier
- JudgmentEscalator + DefaultJudgmentEscalator
- JudgmentResponseHandler — full verification pipeline with data flows
- CaseContextChangedEventHandler.publishJudgment() dispatch
- WorkflowExecutionCompletedHandler.handleGate() → JudgmentScheduler
- CloudEventJudgmentScheduler — binding + gate paths
- DagNode judgment field (engine#1000)
- YAML judgment: block parsing
- Generator schema with Judgment type
- 7 JUDGMENT_* event types
- HumanTaskTarget, ActionGateScheduler, OversightGateService deleted
- PendingJudgment on CaseInstance (in-memory, ConcurrentHashMap)

**Blocks (all complete):**
- LlmJudgmentScheduler — LLM-as-caller for judgment yields (3 tests)
- SchemaValidationVerifier — resolution type validation (5 tests)
- LlmEvaluationVerifier — LLM evaluates response quality
- JudgmentPhase SPI — blocks core (JudgmentPhase, JudgmentDecision, JudgmentContext)
- ExecutionModel 12th component — nullable JudgmentPhase, backward-compatible
- AbstractExecutionDriver loop integration — judgment between aggregation and termination
- LlmJudgmentPhase — inline LLM judgment within patterns (5 tests)
- PatternJudgmentConfig — configuration record with JudgmentMode
- YAML `judgment:` block parsing in PatternWorkerFunctionProvider (6 tests)
- PatternWorkerFunctionHandler — resolves JudgmentPhase, injects into ExecutionModel
- AbstractPatternBuilder.judgment() — Java DSL (3 tests)
- 1917 blocks tests pass, 44 engine-adapter tests pass

**Qhorus (all complete — landed in original repo):**
- qhorus#412 — judgment caller routing via E4 (CLOSED)
- qhorus#413 — judgment compliance evidence for E5 (CLOSED)
- qhorus#414 — formal verification invariants for E7 (CLOSED)

## Remaining

- **GateCompletionApplier merge** — into PlanItemCompletionApplier (deferred until gate handlers consolidated)
- **A2AJudgmentPhase** — handler resolution supports A2A callers but no implementation class (deferred)
- **Human callers in patterns** — requires mid-pattern yield (deferred per spec)

## Cross-Module

**Enabled:**
- Engine-api SNAPSHOT installed with all Judgment types
- Blocks compiles and tests pass against new SNAPSHOT
- Ledger#200, #201 closed; qhorus E4 (#401) closed
- All qhorus governance issues (#412-414) closed
