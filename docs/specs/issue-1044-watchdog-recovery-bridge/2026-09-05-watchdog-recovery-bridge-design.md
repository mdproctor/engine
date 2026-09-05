# Watchdog → Recovery Bridge, Circular Dependency Fix, and Flow Timeout Fix

Covers: engine#1044, engine#974, engine#1023

## 1. Watchdog → Recovery Bridge (engine#1044)

### Problem

Qhorus detects stalls (agent stale, barrier stuck, loop detected, etc.) via
`WatchdogEvaluationService` and fires `WatchdogAlertEvent`. Engine has a full
recovery pipeline (`RecoveryCoordinator`, `ErrorClassifier`, plan adaptation,
goal replan). Nothing connects them — when qhorus detects a stall, engine
doesn't know.

### Architecture

```
qhorus WatchdogEvaluationService
        │
        ▼
  WatchdogAlertEvent (CDI @ObservesAsync)
        │
        ▼
┌────────────────────────────────────────────────┐
│  casehub-engine-watchdog (new module)           │
│                                                 │
│  WatchdogAlertObserver                          │
│       │  resolves caseId via                    │
│       │  CaseChannel.parseCaseId(channelName)   │
│       │  publishes StallRecoveryContext          │
│       │  on Vert.x event bus                    │
│       ▼                                         │
│  StallRecoveryDispatchHandler                   │
│       │  @ConsumeEvent(blocking = true)         │
│       │  resolves binding via PlanItemStore      │
│       │  invokes StallRecoveryHandler SPI       │
│       ▼                                         │
│  DefaultStallRecoveryHandler                    │
│       │  classifies via StallClassifier          │
│       │  executes action with idempotency guards │
│       ▼                                         │
│  recovery actions                               │
│       retry / reroute / escalate / cancel /      │
│       expire / notify / ignore                  │
└────────────────────────────────────────────────┘
```

### New Types

#### engine-api (`io.casehub.api.model`)

**`StallRecoveryAction`** — enum of recovery actions:
- `RETRY` — publish `CONTEXT_CHANGED` to re-trigger dispatch (case-level)
- `REROUTE` — exclude stalled agent, publish `CONTEXT_CHANGED` (binding-level)
- `ESCALATE` — create a human judgment via `JudgmentRequest` (case-level)
- `CANCEL` — cancel the stalled PlanItem (binding-level)
- `NOTIFY` — alert but don't intervene (case-level)
- `EXPIRE` — treat as timeout via `WorkerOutcomeResolvedEvent` (binding-level)
- `IGNORE` — no action

**`StallRecoveryPolicy`** — per-case config on `CaseDefinition`:
- `enabled` (boolean, default false)
- `classifierId` (String, default `"policy-lookup"`)
- `conditionActions` (`Map<WatchdogConditionType, StallRecoveryAction>`)
- `defaultAction` (StallRecoveryAction, default NOTIFY)

YAML:
```yaml
spec:
  stallRecoveryPolicy:
    enabled: true
    classifierId: policy-lookup
    defaultAction: notify
    conditionActions:
      AGENT_STALE: notify
      BARRIER_STUCK: escalate
      LOOP_DETECTED: cancel
      CHANNEL_IDLE: retry
      QUEUE_DEPTH: notify
      CONTEXT_PRESSURE: expire
      OBLIGATION_FAN_OUT: escalate
      CONVERSATION_STALL: escalate
      ECHO_CHAMBER: notify
      CIRCULAR_DELEGATION: cancel
      DELIVERY_LAG: expire
      APPROVAL_PENDING: notify
```

**`StallRecoveryContext`** — record carrying alert context into the handler:
- `caseId` (UUID)
- `tenancyId` (String)
- `conditionType` (WatchdogConditionType)
- `affectedAgentIds` (List<String>)
- `alertSummary` (String)
- `alertContext` (AlertContext — the sealed qhorus type)
- `firedAt` (Instant)
- `resolvedBindingName` (@Nullable String — pre-resolved by dispatch handler)
- `resolvedPlanItemId` (@Nullable String — pre-resolved by dispatch handler)

#### engine-api (`io.casehub.api.spi.recovery`)

**`StallClassifier`** — SPI extending `NamedStrategy`:
```java
StallRecoveryAction classify(StallClassificationContext context);
```
Returns the action to take. `StallClassificationContext` carries the
`StallRecoveryContext` plus `CaseDefinition` and `StallRecoveryPolicy`.

**`DefaultStallClassifier`** (`@DefaultBean`, id=`"policy-lookup"`) — looks up
the action from `StallRecoveryPolicy.conditionActions`, falls back to
`defaultAction`. Validates that the resolved action is supported for the
condition type (see Condition Type Support Matrix); downgrades unsupported
actions to NOTIFY with a warning log. Consumer classifiers can override
for domain-specific logic.

#### engine-common (`io.casehub.engine.common.spi.recovery`)

**`StallRecoveryHandler`** — SPI interface:
```java
boolean handleStall(StallRecoveryContext context);
```
Returns `true` if recovery was initiated, `false` if no action taken.

Consumer extensibility point: consumers provide alternative implementations
via CDI `@Alternative` or `@Specializes` to override the default recovery
logic. The SPI is in engine-common so consumer implementations don't need
to depend on the watchdog module.

**`NoOpStallRecoveryHandler`** (`@DefaultBean @ApplicationScoped`) — returns
`false`. Active when the watchdog module is not on the classpath.

#### casehub-engine-watchdog module (`io.casehub.engine.watchdog`)

**`WatchdogAlertObserver`** (`@ApplicationScoped`) — `@ObservesAsync WatchdogAlertEvent`.

1. Resolve caseId via case resolution strategy (see below)
2. If caseId is null → log and return (platform-level alert, no case context)
3. Load `CaseInstance` from `CaseInstanceCache`
4. Load `CaseDefinition` from `CaseDefinitionRegistry`
5. Check `StallRecoveryPolicy.enabled` → return if disabled
6. Build `StallRecoveryContext` (without binding resolution — that happens
   on the event bus thread)
7. Publish `StallRecoveryContext` on Vert.x event bus address
   `casehub.stall.recovery`

**`StallRecoveryDispatchHandler`** (`@ApplicationScoped`) —
`@ConsumeEvent(value = "casehub.stall.recovery", blocking = true)`.

Runs on the Vert.x event bus thread, ensuring serialization with other
engine handlers (`WorkerOutcomeResolvedHandler`, `CaseContextChangedEventHandler`,
etc.). Steps:

1. Re-validate case state (case still RUNNING, policy still enabled)
2. Resolve binding via binding resolution strategy (see below)
3. Enrich `StallRecoveryContext` with `resolvedBindingName` and
   `resolvedPlanItemId`
4. Invoke `StallRecoveryHandler` SPI

**`DefaultStallRecoveryHandler`** (`@ApplicationScoped`) — replaces
`NoOpStallRecoveryHandler` when module is on classpath:

1. Classify via `StallClassifier` (selected by `StallRecoveryPolicy.classifierId`)
2. Execute idempotency pre-checks (see Idempotency Guards)
3. Execute action:
   - **RETRY**: publish `CaseContextChangedEvent` on `EventBusAddresses.CONTEXT_CHANGED`
     to re-trigger binding dispatch. Construct with:
     `CaseInstance` from cache, `CaseContext.snapshot()`, `ContextLayer.WORKING`.
   - **REROUTE**: load `MutableCaseContext`, write stalled agent to
     `_diagnostics.<bindingName>.excludedAgents` on the working layer,
     snapshot context, publish `CaseContextChangedEvent` on
     `EventBusAddresses.CONTEXT_CHANGED`.
     Requires `resolvedBindingName` — falls back to NOTIFY if null.
   - **ESCALATE**: create `JudgmentRequest` with `JudgmentPayload.BindingPayload`.
     If `resolvedBindingName` is null, use `"stall-recovery"` as synthetic
     binding name. Invoke `JudgmentScheduler` SPI.
   - **CANCEL**: look up `PlanItemRecord` by `resolvedPlanItemId`, check
     status is `RUNNING`, mark `CANCELLED` via `PlanItemStore.updateStatus()`,
     publish `WorkerOutcomeResolvedEvent` on `EventBusAddresses.WORKER_OUTCOME_RESOLVED`
     with `OutcomeDisposition.FAULT`.
     Requires `resolvedPlanItemId` — falls back to NOTIFY if null.
   - **EXPIRE**: publish `WorkerOutcomeResolvedEvent` on
     `EventBusAddresses.WORKER_OUTCOME_RESOLVED` with
     `OutcomeDisposition.EXHAUSTED`. This enters the existing
     `WorkerOutcomeResolvedHandler` → `RecoveryCoordinator` escalation path.
     Requires `resolvedBindingName` and `resolvedPlanItemId` — falls back
     to NOTIFY if null.
   - **NOTIFY**: write `STALL_DETECTED` EventLog only (observability)
   - **IGNORE**: no-op, return false
4. Write `STALL_RECOVERY_INITIATED` EventLog with metadata: `conditionType`,
   `action`, `affectedAgentIds`, `caseId`, `bindingName`

### Case Resolution

Case resolution uses the deterministic channel naming convention
`"case-{caseId}/{purpose}"` established by `CaseChannel.channelName()`.
`CaseChannel.parseCaseId(channelName)` extracts the caseId — no in-memory
index required.

**Resolution strategy** (in `WatchdogAlertObserver`):

1. Try `CaseChannel.parseCaseId(event.targetName())` — works when the
   watchdog targets a specific case channel
2. If null (wildcard `"*"` target or non-case target), extract channelName
   from the `AlertContext` subtype via sealed-type pattern match:
   - `BarrierStuckContext` → `channelName()`
   - `LoopDetectedContext` → `channelName()`
   - `ContextPressureContext` → `channelName()`
   - `CircularDelegationContext` → `channelName()`
   - `DeliveryLagContext` → `channelName()`
   - `ObligationFanOutContext` → `channelName()`
   - `ConversationStallContext` → `channelName()`
   - `EchoChamberContext` → `channelName()`
   - `QueueDepthContext` → `channelName()`
   - `ChannelIdleContext` → `channelNames()` (process each separately)
   - `AgentStaleContext` → no channel info → null
   - `ApprovalPendingContext` → no channel info → null
3. `CaseChannel.parseCaseId(channelName)` on the extracted name
4. If still null → alert is not case-scoped (platform-level) → skip

### Binding Resolution

For binding-level actions (CANCEL, REROUTE, EXPIRE), the dispatch handler
resolves a specific binding via `PlanItemStore`:

1. `PlanItemStore.findByCaseId(caseId, tenancyId)` → all PlanItems for case
2. Filter for `status == RUNNING`
3. If `AlertContext.affectedAgentIds()` is non-empty:
   - Match `PlanItemRecord.executorName` against affected agent IDs
   - If single match: resolved
   - If multiple matches: select the most recently created
     (`PlanItemRecord.createdAt`)
   - If no match: binding unresolvable → action falls back to NOTIFY
4. If no agent IDs available: binding unresolvable → action falls back to NOTIFY

**Agent ID semantics per AlertContext subtype:**

| Subtype | `affectedAgentIds()` | Identity type |
|---------|---------------------|---------------|
| `LoopDetectedContext` | `[sender]` | Worker name (message sender) |
| `ContextPressureContext` | `[actorId]` | Worker name (message actor) |
| `EchoChamberContext` | `participants` | Worker names (message senders) |
| `AgentStaleContext` | `staleInstanceIds` | Qhorus Instance UUIDs — **not** worker names |
| All others | `List.of()` (default) | N/A |

`AgentStaleContext.staleInstanceIds` are qhorus `Instance.id()` UUIDs from
`instanceStore.scan()`, not YAML worker names. The bridge cannot resolve
these to engine bindings. A future qhorus-api enhancement
(engine#TBD — issue to be filed) could expose Instance → registered agent
name mapping.

### Condition Type Support Matrix

Actions are classified by the data required to execute them:

| Action | Requires | Scope |
|--------|----------|-------|
| RETRY | caseId | Case-level |
| ESCALATE | caseId | Case-level |
| NOTIFY | caseId | Case-level |
| IGNORE | — | No-op |
| REROUTE | caseId + bindingName + agentId | Binding-level |
| CANCEL | caseId + planItemId | Binding-level |
| EXPIRE | caseId + bindingName + planItemId | Binding-level |

Binding-level actions require successful binding resolution. When resolution
fails (no agent IDs, no RUNNING PlanItem match, or agent IDs are not
worker names), the action **downgrades to NOTIFY** with a warning log.

**Condition types by resolution capability:**

- **Full resolution** (channel + worker-name agent IDs):
  `LOOP_DETECTED`, `CONTEXT_PRESSURE`, `ECHO_CHAMBER`
  → All actions supported

- **Case-level only** (channel available, no binding-resolvable agent IDs):
  `BARRIER_STUCK`, `CHANNEL_IDLE`, `CIRCULAR_DELEGATION`, `CONVERSATION_STALL`,
  `DELIVERY_LAG`, `OBLIGATION_FAN_OUT`, `QUEUE_DEPTH`
  → RETRY, ESCALATE, NOTIFY, IGNORE supported. Binding-level actions downgrade.

- **Platform-level** (no case resolution):
  `AGENT_STALE`, `APPROVAL_PENDING`
  → NOTIFY and IGNORE only. All other actions downgrade.

### Idempotency Guards

Watchdog alerts can fire repeatedly for the same condition. Before executing
any action, the handler checks current state:

- **CANCEL**: skip if `PlanItemRecord.status` is not `RUNNING` (already
  CANCELLED, COMPLETED, or FAULTED)
- **RETRY**: skip if a `CONTEXT_CHANGED` event was published for this case
  within the debounce window (tracked via `_stallRecovery.lastRetryAt` on
  the working layer — separate namespace from per-binding `_diagnostics.*`)
- **REROUTE**: skip if agent is already in `_diagnostics.<bindingName>.excludedAgents`
  (set semantics — redundant add is safe but the follow-on `CONTEXT_CHANGED`
  causes an unnecessary dispatch cycle)
- **EXPIRE**: skip if `PlanItemRecord.status` is not `RUNNING`
- **ESCALATE**: skip if a judgment already exists for this binding with
  status PENDING

### Containment Interaction

`WatchdogEvaluationService.fireAlert()` fires the CDI event asynchronously
(`alertEvents.fireAsync()`), then executes containment actions synchronously
(`executeContainmentAction()`). By the time the engine's observer processes
the async event:

- The channel may already be **paused** (`PAUSE_CHANNEL` containment)
- The agent may already be **deregistered** (`DEREGISTER_AGENT` containment)

The handler checks these states before acting:

- **RETRY** after channel pause: the `CONTEXT_CHANGED` publication triggers
  dispatch evaluation, but the dispatcher checks channel status. If the
  channel is paused, dispatch is deferred. This is the correct behavior —
  the retry will execute when the channel is unpaused.
- **REROUTE** after agent deregistration: adding the agent to
  `excludedAgents` is redundant (already offline) but harmless. The
  subsequent dispatch selects a different agent. This is complementary
  behavior.

No additional guards needed — the engine's dispatch layer already handles
paused channels and offline agents. The handler logs containment state
for observability.

### StallRecoveryPolicy vs RecoveryPolicy Interaction

`RecoveryPolicy` and `StallRecoveryPolicy` address different failure
detection paths:

| Aspect | RecoveryPolicy | StallRecoveryPolicy |
|--------|---------------|---------------------|
| **Trigger** | Worker outcome (Failed/Declined/Expired) | Watchdog alert (communication stall) |
| **Classifier** | `ErrorClassifier` (heuristic/custom) | `StallClassifier` (policy-lookup/custom) |
| **Escalation** | 3-level (Transient → Reasoning → Fundamental) | Direct action from policy |
| **Scope** | Per-binding with overrides | Per-condition-type |

**Precedence:** These policies trigger in different circumstances. A worker
outcome fires `RecoveryPolicy`; a watchdog alert fires `StallRecoveryPolicy`.
They do not compete for the same event.

**EXPIRE bridge:** When `StallRecoveryPolicy` resolves to EXPIRE, the handler
publishes a `WorkerOutcomeResolvedEvent` with `OutcomeDisposition.EXHAUSTED`.
This enters `RecoveryPolicy`'s domain — `WorkerOutcomeResolvedHandler` marks
the PlanItem FAULTED and triggers compound completion evaluation. This is
intentional: EXPIRE converts a communication stall into a worker outcome,
bridging the two recovery paths.

**Shared state:** Both paths write to `_diagnostics.<bindingName>` in the
case context (attempt counts, excluded agents). Operations are idempotent
(set semantics for excludedAgents, monotonic increment for attempts).

### New Event Types

- `CaseHubEventType.STALL_DETECTED` — observability (NOTIFY action)
- `CaseHubEventType.STALL_RECOVERY_INITIATED` — audit (any non-NOTIFY action)

### Module Dependencies

```xml
<dependencies>
    <dependency>casehub-engine-common</dependency>    <!-- StallRecoveryHandler SPI, PlanItemStore -->
    <dependency>casehub-engine-api</dependency>        <!-- StallRecoveryPolicy, StallClassifier, types -->
    <dependency>casehub-qhorus-api</dependency>        <!-- WatchdogAlertEvent, AlertContext -->
    <dependency>casehub-worker-api</dependency>        <!-- WorkerOutcome (for EXPIRE path) -->
    <dependency>quarkus-arc</dependency>
    <dependency>quarkus-vertx</dependency>
</dependencies>
```

No dependency on `casehub-engine` runtime or `casehub-eidos-api`.

### YAML Schema Addition

`CaseDefinition.yaml` gains `stallRecoveryPolicy` in the `spec:` block.
`CaseDefinitionYamlMapper` parses the block. `YamlStallRecoveryPolicy`
generated record. `WatchdogConditionType` is from qhorus-api — the enum
values are string-matched in YAML. `classifierId` field selects the
`StallClassifier` implementation (matched via `NamedStrategy.id()`).

### Test Strategy

- Unit tests for `DefaultStallClassifier`:
  - Policy lookup for each condition type
  - Fallback to defaultAction
  - Binding-level action downgrade when resolution unavailable
- Unit tests for case resolution:
  - `CaseChannel.parseCaseId()` with case channel names
  - AlertContext channel extraction (each subtype)
  - Wildcard `"*"` target handling
  - Non-case channel names → null
- Unit tests for binding resolution:
  - PlanItemStore lookup with RUNNING filter
  - executorName matching against agent IDs
  - Multiple matches → most recent selection
  - No match → null
- Unit tests for `DefaultStallRecoveryHandler` per action type:
  - RETRY: publishes CaseContextChangedEvent correctly
  - REROUTE: writes excludedAgents, publishes CaseContextChangedEvent
  - CANCEL: marks PlanItem CANCELLED, publishes WorkerOutcomeResolvedEvent
  - EXPIRE: publishes WorkerOutcomeResolvedEvent with EXHAUSTED
  - ESCALATE: creates JudgmentRequest with BindingPayload
  - NOTIFY: writes EventLog only
  - IGNORE: returns false
- Idempotency tests:
  - CANCEL when PlanItem already in terminal state → skipped
  - REROUTE when agent already excluded → skipped
  - EXPIRE when PlanItem not RUNNING → skipped
- Edge-case tests:
  - ChannelCaseIndex lookup returning null (non-case channel)
  - StallRecoveryPolicy disabled → handler short-circuits
  - Concurrent alerts for same case/binding → idempotency guard
  - CANCEL when PlanItem is already COMPLETED → skip
  - Alert types with no channel info (ApprovalPendingContext) → skip
  - Multiple RUNNING PlanItems → most recent selected
  - Binding-level action with no binding resolution → downgrade to NOTIFY
- Integration test: end-to-end alert → recovery action with
  `casehub-persistence-memory` + mock `WatchdogAlertEvent`

---

## 2. Circular Dependency Fix (engine#974)

### Problem

Engine and work have a circular repo-level build dependency. Neither can be
built from source without the other's artifacts already published.

### Root Cause

Two engine modules violate the dependency boundary by importing work runtime:

1. **`casehub-engine-inbound`** — `InboundWorkItemBridge` directly calls
   `WorkItemService.create()` and `TenantContextRunner`. Should use an SPI.
2. **`casehub-engine-actor-state`** — `WorkActorStateContributor` queries
   `WorkItemStore`. This is a work concern (contributing work's data to a
   shared actor view) living in the wrong repo.

### Fix

#### 2a. engine-inbound: SPI extraction

Define `InboundWorkItemScheduler` in engine-common:
```java
public interface InboundWorkItemScheduler {
    void schedule(InboundWorkItemRequest request);
}
```

`InboundWorkItemRequest` — record in engine-common carrying the fields
`InboundWorkItemBridge` currently passes to `WorkItemService.create()`.

`NoOpInboundWorkItemScheduler` (`@DefaultBean @ApplicationScoped`, runtime) —
logs warning that work integration is not available.

Work provides the implementation in `casehub-work-engine-adapter` (or a new
module), injecting `WorkItemService` and translating the request.

`InboundWorkItemBridge` is refactored to inject `InboundWorkItemScheduler`
instead of `WorkItemService`. The `casehub-work` compile dependency is removed
from `casehub-engine-inbound/pom.xml`.

#### 2b. engine-actor-state: contributor relocation

Move `WorkActorStateContributor` to the work repo. The `ActorStateContributor`
SPI is in `casehub-platform-api`, which work already depends on.

In `casehub-engine-actor-state/pom.xml`, remove the `casehub-work` and
`casehub-qhorus` compile dependencies. The module keeps only engine-specific
contributors (`EngineActorStateContributor` using `WorkerExecutionManager`).

The work repo gains a new module or adds the contributor to
`casehub-work-engine-adapter`.

#### Resulting Dependency DAG

```
casehub-work-api  (no engine dependency)
       ↑
   engine modules  (depend on work-api at most)
       ↑
casehub-work runtime + work-engine-adapter  (depend on engine modules)
```

Build order: `work-api → engine → work`. Clean DAG, no cycle.

#### Execution Constraint

Requires a work-slot with both engine and work repos for coordinated
cross-repo refactoring.

---

## 3. FlowWorkerFunctionHandler Timeout Fix (engine#1023)

### Problem

`YamlSimpleCaseHubBeanTest.testExecution` fails intermittently in CI. The test
is tagged `@Tag("flaky")` and uses a 10-second Awaitility timeout.

### Root Cause

`FlowWorkerFunctionHandler.execute()` receives `timeoutMs` (line 74) but
ignores it. Line 86 calls `CompletableFuture.join()` — indefinite blocking.

Every other `WorkerFunctionHandler` enforces the timeout:
- `SyncAgentWorkerFunctionHandler`: `future.get(timeoutMs, MILLISECONDS)`
- `A2AWorkerFunctionHandler`: same
- `McpWorkerFunctionHandler`: same
- `ReActWorkerFunctionHandler`: same

Under CI load, the SWF workflow execution takes > 10s, `.join()` blocks
indefinitely, the case stays RUNNING, and the test's Awaitility timeout fires.

### Fix

Replace `.join()` with timeout-enforced execution:

```java
CompletableFuture<WorkflowModel> future = executeWorkflow(...);
try {
    WorkflowModel model = future.get(timeoutMs, TimeUnit.MILLISECONDS);
    // ... existing output mapping
} catch (TimeoutException e) {
    future.cancel(true);
    registry.remove(instanceId);
    return new HandlerResult(WorkerResult.expired(
        "Flow workflow timed out after " + timeoutMs + "ms"));
} catch (ExecutionException e) {
    return new HandlerResult(WorkerResult.failed(
        "Flow workflow failed: " + e.getCause().getMessage()));
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    return new HandlerResult(WorkerResult.failed(
        "Flow workflow interrupted"));
}
```

The `executeWorkflow()` method must return the `CompletableFuture` (already
does) AND expose the `instanceId` so the timeout path can call
`registry.remove(instanceId)`. Refactor to extract `instanceId` before the
`try` block.

Key changes from the original fix:
- **`InterruptedException` handling**: catches and restores the interrupt flag
  via `Thread.currentThread().interrupt()`
- **Future cancellation on timeout**: calls `future.cancel(true)` to interrupt
  the running workflow. The `whenComplete` callback on the future (which calls
  `registry.remove`) may or may not fire after cancellation depending on the
  SWF implementation, so the timeout path explicitly calls `registry.remove()`
  to prevent resource leaks.

Additionally:
- Remove `@Tag("flaky")` from the test
- Clean up duplicate entries in `application.properties` `selected-alternatives`

---

## References

- `common/src/main/java/io/casehub/engine/common/spi/recovery/RecoveryCoordinator.java` — existing worker-failure recovery SPI
- `common/src/main/java/io/casehub/engine/common/spi/recovery/RecoveryContext.java` — worker-failure context shape
- `runtime/src/main/java/io/casehub/engine/internal/recovery/DefaultRecoveryCoordinator.java` — existing 3-level escalation
- `api/src/main/java/io/casehub/api/model/CaseChannel.java` — channel naming convention and `parseCaseId()` utility
- `common/src/main/java/io/casehub/engine/common/spi/PlanItemStore.java` — PlanItem persistence SPI
- `common/src/main/java/io/casehub/engine/common/internal/model/PlanItemRecord.java` — PlanItem record with `bindingName`, `executorName`, `status`
- `common/src/main/java/io/casehub/engine/common/internal/event/EventBusAddresses.java` — Vert.x event bus addresses
- `common/src/main/java/io/casehub/engine/common/internal/event/WorkerOutcomeResolvedEvent.java` — worker outcome event for EXPIRE bridge
- `common/src/main/java/io/casehub/engine/common/internal/event/CaseContextChangedEvent.java` — context changed event for RETRY/REROUTE
- `common/src/main/java/io/casehub/engine/common/spi/JudgmentRequest.java` — unified judgment scheduling (replaces deprecated `JudgmentScheduleRequest`)
- `common/src/main/java/io/casehub/engine/common/spi/JudgmentPayload.java` — judgment payload variants
- `qhorus/api/src/main/java/io/casehub/qhorus/api/watchdog/WatchdogAlertEvent.java` — alert event record
- `qhorus/api/src/main/java/io/casehub/qhorus/api/watchdog/WatchdogConditionType.java` — 12 condition types
- `qhorus/api/src/main/java/io/casehub/qhorus/api/watchdog/AlertContext.java` — sealed context hierarchy
- `flow/src/main/java/io/casehub/engine/flow/FlowWorkerFunctionHandler.java:74,86` — timeout param ignored, .join() blocks
- `runtime/src/main/java/io/casehub/engine/internal/executor/SyncAgentWorkerFunctionHandler.java:149` — correct timeout pattern
- `casehub-engine-inbound/src/main/java/.../InboundWorkItemBridge.java` — direct WorkItemService call (boundary violation)
- `casehub-engine-actor-state/src/main/java/.../WorkActorStateContributor.java` — work concern in wrong repo
- PP-20260727-5267d2 — plan-type module boundary protocol
- PP-20260722-60e519 — cross-repo source verification protocol
- engine#974 issue body — full dependency matrix
