# HANDOFF — engine#994 Governed Yield

**Branch:** `issue-994-governed-yield`
**Date:** 2026-08-27

## Last Session

Wired JudgmentResponseHandler.processResponse() end-to-end: CaseInstanceCache integration, PendingJudgment on CaseInstance, verification → accepted/fault data flows (binding context update, gate re-fire, PlanItem fault). Implemented engine#1000 DagNode judgment field (6th field, backward-compatible). Implemented blocks#171 tests and blocks#172 verification strategies (SchemaValidationVerifier, LlmEvaluationVerifier). Fixed generator schema (MissingNode crash, CaseDefinitionSpec overwrite). Completed ActionGateScheduler → JudgmentScheduler unification. Deleted OversightGateService. All CLAUDE.md references updated.

Prior sessions: designed spec (14 decisions), implemented all 6 batches, completed HumanTaskTarget → JudgmentTarget migration.

- Spec: `docs/specs/issue-994-governed-yield/2026-08-26-governed-yield-design.md`
- Plan: `docs/plans/2026-08-26-governed-yield.md`

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

**Blocks (blocks#171, #172 done; #173 deferred):**
- LlmJudgmentScheduler — LLM-as-caller for judgment yields (3 tests)
- SchemaValidationVerifier — resolution type validation (5 tests)
- LlmEvaluationVerifier — LLM evaluates response quality
- blocks#173 yield-aware patterns deferred — requires cross-module auto-generation of judgment bindings from pattern config

## Remaining

- **blocks#173** — yield-aware patterns (deferred, cross-module complexity)
- **qhorus#412-414** — governance integration (user working on qhorus E5 #402 in original repo)
- **GateCompletionApplier merge** — into PlanItemCompletionApplier (deferred until gate handlers consolidated)

## Cross-Module

**Enabled:**
- Engine-api SNAPSHOT installed with all Judgment types
- Blocks compiles and tests pass against new SNAPSHOT
- Ledger#200, #201 closed; qhorus E4 (#401) closed — qhorus integration unblocked
