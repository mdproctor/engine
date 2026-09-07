# Design: Compensation GraphQL Subscriptions + Enriched Timeline (#1048)

**Issue:** casehubio/engine#1048
**Parent:** casehubio/work#238 (saga compensation), casehubio/work#390 (visualization APIs)
**Date:** 2026-09-07
**Status:** Draft

---

## 1. Problem

The compensation visualization API (engine-graphql) lacks three capabilities the ops dashboard needs:

1. **No real-time step progress** — Case-level compensation transitions (COMPENSATING, COMPENSATED, COMPENSATION_FAULTED) flow through the existing `caseLifecycle` subscription via `CaseLifecycleEvent`. But step-level events (`COMPENSATION_STEP_STARTED`, `COMPENSATION_STEP_COMPLETED`) are written directly to EventLog by `CaseCompensationServiceImpl.appendStepEvent()` without firing CDI events — `CaseEventPublisher` never sees them. The dashboard cannot track step-by-step compensation progress in real time.

2. **No error detail on faulted steps** — `CompensationStepType` has no error fields. When a compensation step faults, the error reason lands in `_diagnostics.<bindingName>` and on the case-level `COMPENSATION_FAULTED` event's metadata, but the step record in the timeline shows only status=FAULTED with no explanation.

3. **No retry or sub-case tracking** — When a COMPENSATION_FAULTED case is retried, the timeline returns a flat list of steps with no attempt grouping. When parent compensation triggers child case compensation, the timeline has no link to the child cases.

---

## 2. Goals

1. Real-time step-level compensation subscription for ops dashboard
2. Error reason and failure category on faulted compensation steps
3. Retry tracking with per-attempt grouping
4. Sub-case compensation linkage

## 3. Non-Goals

- Blocks-ui dashboard component (separate work in pages repo)
- Modifying the existing `caseLifecycle` subscription
- Changing the compensation coordinator's execution logic
- Historical compensation analytics or aggregation queries

---

## 4. Changes

### 4.1 CompensationStepEvent — New CDI Event (engine-common)

A new CDI event type for step-level compensation events, fired alongside the existing EventLog writes:

```java
package io.casehub.engine.common.internal.event;

public record CompensationStepEvent(
    UUID caseId,
    String tenancyId,
    CaseHubEventType eventType,
    String originalBindingName,
    String compensatingBindingName,
    Instant timestamp) {}
```

`CaseCompensationServiceImpl.appendStepEvent()` gains a CDI `Event<CompensationStepEvent>` injection and fires the event after writing the EventLog entry. The EventLog write remains the source of truth; the CDI event is the observation signal for subscriptions.

### 4.2 CaseEventPublisher — Third Emitter Stream

`CaseEventPublisher` gains:

```java
private final List<MultiEmitter<? super CompensationStepEvent>>
    compensationStepEmitters = new CopyOnWriteArrayList<>();

void onCompensationStepEvent(@ObservesAsync CompensationStepEvent event) {
  for (var emitter : compensationStepEmitters) {
    emitter.emit(event);
  }
}

public Multi<CompensationStepEvent> compensationStepStream() {
  return Multi.createFrom()
      .<CompensationStepEvent>emitter(
          emitter -> {
            compensationStepEmitters.add(emitter);
            emitter.onTermination(() -> compensationStepEmitters.remove(emitter));
          },
          BackPressureStrategy.DROP);
}
```

Same pattern as the existing lifecycle and context-change streams.

### 4.3 CompensationProgressEventType — New GraphQL DTO

```java
package io.casehub.engine.graphql.dto;

@Type("CompensationProgressEvent")
public record CompensationProgressEventType(
    UUID caseId,
    String eventType,
    String originalBindingName,
    String compensatingBindingName,
    Instant timestamp) {

  public static CompensationProgressEventType from(CompensationStepEvent event) {
    return new CompensationProgressEventType(
        event.caseId(),
        event.eventType().name(),
        event.originalBindingName(),
        event.compensatingBindingName(),
        event.timestamp());
  }
}
```

### 4.4 CaseSubscriptionResolver — New Subscription

```java
@Subscription
@Description("Live compensation step progress — step starts and completions")
public Multi<CompensationProgressEventType> compensationProgress(
    @Name("caseId") UUID caseId) {
  return publisher
      .compensationStepStream()
      .filter(event -> event.caseId().equals(caseId))
      .map(CompensationProgressEventType::from);
}
```

### 4.5 CompensationStepType — Error Enrichment

`CompensationStepType` gains two nullable fields:

```java
@Type("CompensationStep")
public record CompensationStepType(
    String planItemId,
    String bindingName,
    String targetType,
    String status,
    Instant createdAt,
    Instant completedAt,
    String compensatesBinding,
    String compensatesItemId,
    String errorReason,         // NEW — human-readable error description
    String failureCategory) {}  // NEW — Transient/Knowledge/Infeasible
```

**Data source:** For FAULTED steps, the timeline assembly queries `_diagnostics.<bindingName>` from the case context's working layer. The `latestDiagnosis` field carries `category` (from `FailureCategory.categoryName()`) and the error reason. For non-faulted steps, both fields are null.

### 4.6 CompensationAttemptType — New GraphQL DTO

```java
package io.casehub.engine.graphql.dto;

@Type("CompensationAttempt")
public record CompensationAttemptType(
    int attemptNumber,
    Instant startedAt,
    Instant completedAt,
    String outcome,
    String triggeredBy,
    String reason,
    List<CompensationStepType> steps) {}
```

`outcome` values: `COMPLETED`, `FAULTED`, `IN_PROGRESS` (current attempt, no completion event yet).

### 4.7 CompensationTimelineType — Restructured

```java
@Type("CompensationTimeline")
public record CompensationTimelineType(
    UUID caseId,
    String status,
    List<TimelineStepType> forwardSteps,
    List<CompensationAttemptType> attempts,          // CHANGED — was flat compensationSteps
    List<UUID> childCompensationCaseIds) {}           // NEW
```

**Removed fields:** `triggeredBy`, `reason`, `compensationStartedAt`, `compensationCompletedAt` — these move into `CompensationAttemptType` where they are per-attempt. The top-level `status` remains (current case status).

### 4.8 Timeline Assembly — Attempt Grouping

The `compensationTimeline` query in `CaseQueryResolver` is restructured:

1. Query all compensation EventLog entries for the case (STARTED, COMPLETED, FAULTED, STEP_STARTED, STEP_COMPLETED)
2. Group by attempt: each `COMPENSATION_STARTED` entry starts a new attempt
3. Within each attempt, partition PlanItems by creation time windows (steps created between attempt N's start and attempt N+1's start belong to attempt N)
4. For the last attempt with no COMPLETED/FAULTED event, set outcome to `IN_PROGRESS`
5. Enrich FAULTED steps with `errorReason` and `failureCategory` from `_diagnostics`
6. Populate `childCompensationCaseIds` from EventLog metadata on `COMPENSATION_STEP_STARTED` entries where the compensating binding has a `SubCaseTarget`

**Step-to-attempt assignment logic:** PlanItemRecords carry `createdAt` timestamps. Each attempt is bounded by its `COMPENSATION_STARTED` timestamp (inclusive) and the next attempt's `COMPENSATION_STARTED` timestamp (exclusive). This naturally groups steps that were created during each attempt without requiring an explicit `attemptNumber` on PlanItem.

### 4.9 CaseCompensationServiceImpl — CDI Event Firing

Two changes to `appendStepEvent()`:

```java
@Inject Event<CompensationStepEvent> compensationStepEvent;

private void appendStepEvent(
    CaseInstance instance,
    CaseHubEventType type,
    String originalBindingName,
    String compensatingBindingName) {
  // ... existing EventLog write ...

  compensationStepEvent.fireAsync(new CompensationStepEvent(
      instance.getUuid(),
      instance.tenancyId,
      type,
      originalBindingName,
      compensatingBindingName,
      Instant.now()));
}
```

`fireAsync` is non-blocking — the compensation coordinator is not slowed by subscription delivery.

---

## 5. Module Impact

| Module | Changes |
|--------|---------|
| `engine-common` | New `CompensationStepEvent` record |
| `engine-planning` | `CaseCompensationServiceImpl` — CDI event firing in `appendStepEvent()` |
| `engine-graphql` | New subscription, new DTOs, restructured timeline assembly |

No changes to: engine-api, engine-rest, engine-ledger, work repos, connectors, qhorus.

---

## 6. GraphQL Schema Surface

**New subscription:**
```graphql
type Subscription {
  compensationProgress(caseId: ID!): CompensationProgressEvent
}
```

**New types:**
```graphql
type CompensationProgressEvent {
  caseId: ID!
  eventType: String!
  originalBindingName: String!
  compensatingBindingName: String!
  timestamp: DateTime!
}

type CompensationAttempt {
  attemptNumber: Int!
  startedAt: DateTime!
  completedAt: DateTime
  outcome: String!
  triggeredBy: String
  reason: String
  steps: [CompensationStep!]!
}
```

**Modified types:**
```graphql
type CompensationTimeline {
  caseId: ID!
  status: String!
  forwardSteps: [TimelineStep!]!
  attempts: [CompensationAttempt!]!          # was: compensationSteps
  childCompensationCaseIds: [ID!]!           # new
}

type CompensationStep {
  # ... existing fields ...
  errorReason: String                        # new
  failureCategory: String                    # new
}
```

---

## 7. Event Flow — Step Subscription

```
CaseCompensationServiceImpl.fireNextCompensationStep()
    │ creates compensating PlanItem, fires binding
    │
    ├── appendStepEvent(COMPENSATION_STEP_STARTED)
    │       │ writes EventLog entry (existing)
    │       │ fires CompensationStepEvent via CDI fireAsync (NEW)
    │       │
    │       └── CaseEventPublisher.onCompensationStepEvent(@ObservesAsync)
    │               │ emits to compensationStepEmitters
    │               │
    │               └── CaseSubscriptionResolver.compensationProgress(caseId)
    │                       │ filters by caseId
    │                       │ maps to CompensationProgressEventType
    │                       │
    │                       └── GraphQL WebSocket → dashboard
    │
    └── (PlanItem completes or faults)
            │
            └── onCompensationPlanItemStateChanged(@ObservesAsync)
                    │
                    └── appendStepEvent(COMPENSATION_STEP_COMPLETED)
                            │ same CDI event path as above
```

---

## 8. Testing Strategy

### 8.1 Unit Tests

**CompensationStepEventTest:**
- Verify CDI event is fired with correct fields on appendStepEvent
- Verify event carries caseId, tenancyId, event type, binding names

**CaseEventPublisherTest (extended):**
- Verify compensationStepStream emits received CompensationStepEvents
- Verify emitter cleanup on subscription termination

**CompensationProgressEventTypeTest:**
- Verify `from()` mapping from CompensationStepEvent

**CompensationAttemptTypeTest:**
- Verify attempt grouping logic with single attempt (normal case)
- Verify attempt grouping with multiple attempts (retry scenario)
- Verify IN_PROGRESS outcome for current attempt

**CompensationTimelineType restructured assembly:**
- Single attempt, all steps complete → outcome COMPLETED
- Single attempt, step faults → outcome FAULTED, errorReason populated
- Two attempts: first faults, second completes → two attempts with correct step partitioning
- Sub-case compensation → childCompensationCaseIds populated
- No compensation events → returns null (unchanged)

### 8.2 Integration Tests

**CaseSubscriptionResolverTest (extended):**
- Subscribe to `compensationProgress`, trigger compensation, verify step events arrive
- Verify caseId filtering — events for other cases are not delivered

---

## 9. Implementation Sequence

1. **CompensationStepEvent** — new record in engine-common, no dependencies
2. **CaseCompensationServiceImpl** — inject CDI Event, fire in appendStepEvent
3. **CaseEventPublisher** — third emitter stream for compensation step events
4. **CompensationProgressEventType** — new DTO
5. **CaseSubscriptionResolver** — new `compensationProgress` subscription
6. **CompensationStepType** — add errorReason, failureCategory fields
7. **CompensationAttemptType** — new DTO
8. **CompensationTimelineType** — restructure with attempts + childCompensationCaseIds
9. **CaseQueryResolver** — rewrite timeline assembly with attempt grouping + error enrichment
10. **Tests** — TDD throughout

---

## 10. References

- `CaseSubscriptionResolver.java:31-57` — existing subscription pattern
- `CaseEventPublisher.java:27-65` — CDI observer → Multi emitter pattern
- `CaseCompensationServiceImpl.java:378-393` — appendStepEvent (EventLog-only, no CDI event)
- `CaseCompensationServiceImpl.java:264-299` — onCompensationPlanItemStateChanged (fault handling)
- `CaseCompensationServiceImpl.java:356-362` — transitionToFaulted (error detail in metadata)
- `CompensationStepType.java:22-30` — current record, no error fields
- `CompensationTimelineType.java:24-32` — current record, flat structure
- `CaseQueryResolver.java:157-268` — current timeline assembly (flat, no grouping)
- `CaseHubEventType.java:101-105` — 5 compensation event type constants
- `FailureCategory` sealed interface — Transient/Knowledge/Infeasible classification
- `2026-09-01-saga-compensation-design.md` §5 — saga coordinator architecture
- `2026-09-04-compensation-visualization-design.md` §4 — original timeline design
- casehubio/engine#1048 — issue
- D1-D4 in decisions.md — design decisions
