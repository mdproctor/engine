# Qhorus Commitment Bridge — DECLINE/FAILED → WorkerOutcome

**Issue:** engine#515
**Date:** 2026-06-18

## Problem

`QhorusMessageSignalBridge` treats all commitment-resolving messages (DONE, RESPONSE, DECLINE, FAILURE) identically — writes a `channelMessage` signal. DECLINE and FAILURE speech acts should instead enter the engine's failure cascade (`handleSemanticFailure` in `WorkflowExecutionCompletedHandler`), producing the same `_outcomes` tracking, PlanItem transitions, and exhaustion detection as engine-internal worker failures.

## Design

Single class change to `QhorusMessageSignalBridge`. Fork on message type:

- **DONE/RESPONSE** → existing `channelMessage` signal path (unchanged)
- **DECLINE/FAILURE** → resolve engine context from `correlationId`, publish `WorkflowExecutionCompleted` on `WORKER_EXECUTION_FINISHED` with `WorkerOutcome.declined(content)` or `WorkerOutcome.failed(content)`

### Context Resolution

The engine sets `correlationId = String.valueOf(eventLogId)` when dispatching COMMANDs via `CaseChannelProvider.postToChannel()`. The EventLog metadata carries `workerName`, `bindingName`, and `inputDataHash`.

Resolution chain:
1. Parse `correlationId` as Long → `eventLogId`
2. `CrossTenantEventLogRepository.findById(eventLogId)` → EventLog with metadata
3. `CrossTenantCaseInstanceRepository.findByUuid(caseId)` → CaseInstance
4. Extract `workerName` and `bindingName` from EventLog metadata
5. Resolve Worker from CaseDefinition; if not found, construct minimal Worker with just the name (EventLog is the source of truth — the worker existed at dispatch time)
6. Publish `WorkflowExecutionCompleted` with the appropriate outcome

### Edge Cases

| Condition | Meaning | Action |
|-----------|---------|--------|
| correlationId doesn't parse as Long | Not engine-dispatched — non-engine Qhorus interaction | Fall through to existing signal path |
| EventLog not found | Same — coincidental numeric correlationId | Fall through to existing signal path |
| workerName missing from EventLog metadata | Data corruption (should never happen) | Log error, fall through to signal path |
| CaseInstance not found | Case already terminal | Log info, return (no cascade, no signal) |
| Worker not in CaseDefinition | Definition changed since dispatch | Construct Worker with name from metadata — failure cascade only needs the name when bindingName is present |

### Dependencies Added to Bridge

- `@CrossTenant CrossTenantEventLogRepository`
- `@CrossTenant CrossTenantCaseInstanceRepository`
- `CaseDefinitionRegistry`
- `EventBus` (Vert.x mutiny)

All are `runtime`-internal. The `@ObservesAsync` handler runs on a managed thread — blocking `.await().atMost()` calls follow the established `PlanItemCompletionApplier` pattern.
