# CaseHub Engine — Architecture and Design

> **Status:** Active development (casehub-engine)
> **Version:** 1.0.0 (in progress)

## Overview

casehub-engine is a **hybrid choreography+orchestration coordination engine** for multi-agent work. It extends the Blackboard Architecture (Hayes-Roth, 1985) using CMMN (Case Management Model and Notation) terminology. Two complementary execution models share the same worker selection infrastructure and Quartz-based execution layer, enabling both spontaneous self-organisation (choreography) and deliberate coordination (orchestration) in the same case.

## Architecture Layers

### Core Model (`engine-model`)

Plain POJOs with no Quarkus or JPA dependencies:

- **Domain objects:** `CaseMetaModel`, `CaseInstance`, `EventLog`
- **SPI interfaces:** `CaseMetaModelRepository`, `CaseInstanceRepository`, `EventLogRepository`
- **Enums:** `CaseStatus` (in `engine-model`)  
- **Event types** (in `api`): `CaseHubEventType`, `EventStreamType` — moved to API module to support public eventLog() methods
- **CDI events:** `CaseLifecycleEvent` — fired via `Event.fireAsync()` by lifecycle handlers; optional modules observe this to react to transitions without coupling to the engine

### Persistence (`casehub-persistence-hibernate`, in-memory test variant)

Manages storage and retrieval of domain objects. Uses JPA/Panache for production, in-memory map for tests.

### Engine (`engine`)

Orchestrates case execution via:

- **`CaseContextChangedEventHandler`** — watches for context changes, evaluates bindings, triggers choreography
- **`WorkerScheduleEventHandler`** — calls `WorkerContextProvider.buildContext()` (timing contract), schedules work in Quartz via `QuartzWorkerExecutionJob` which re-calls `buildContext` and sets `WorkerExecutionContext` for the worker function
- **`WorkflowExecutionCompletedHandler`** — processes work completion, resumes WAITING cases
- **`EventLog`** — persistent audit trail of all decisions and state changes

Lifecycle handlers fire `CaseLifecycleEvent` via `Event.fireAsync()` after their EventLog write. If no observer is registered (e.g. `casehub-ledger` absent), the event fires into the void — zero overhead.

### Audit Ledger (`casehub-ledger`, optional)

An optional module that writes an immutable, hash-chained audit record for every significant case lifecycle transition. Depends on `engine-model` (for `CaseLifecycleEvent`) and `casehub-ledger` — no dependency on the `engine` module itself.

| Class | Role |
|---|---|
| `CaseLedgerEntry` | `LedgerEntry` subclass (JOINED inheritance) — adds `caseId`, `commandType`, `eventType`, `caseStatus` |
| `CaseLedgerEntryRepository` | Extends `JpaLedgerEntryRepository`; `@ApplicationScoped` activates it as the CDI `LedgerEntryRepository` bean |
| `CaseLedgerEventCapture` | `@ObservesAsync CaseLifecycleEvent` — writes entry in its own `@Transactional` block on a managed executor thread |

**Flyway migration:** V2000 (`case_ledger_entry` table + FK to `ledger_entry`). V1000–V1004 are reserved by casehub-ledger.

**Observed transitions:**

| Command | Event | Notes |
|---|---|---|
| StartCase | CaseStarted | First entry — seq=1 |
| SuspendCase | CaseSuspended | Admin pause |
| ResumeCase | CaseResumed | |
| SubmitWork | WorkSubmitted | WAITING transition |
| CompleteWork | WorkCompleted | Resume from WAITING |
| SignalCase | SignalReceived | External trigger |
| ReachMilestone | MilestoneReached | |
| ReachGoal | GoalReached | |
| CompleteCase | CaseCompleted | Final entry |
| FaultCase | CaseFaulted | Error termination |
| CancelCase | CaseCancelled | |
| ExecuteWorker / WorkerExecutionStarted | Worker began | |
| ExecuteWorker / WorkerExecutionCompleted | Worker finished | |

**Actor type inference:** `"system"` or null → `SYSTEM`; versioned persona (`model:persona@version`, e.g. `claude:casehub-agent@v1`) → `AGENT`; anything else → `HUMAN`.

**Eventual consistency note:** `CaseLedgerEventCapture` runs in a separate transaction from the case state update (required by `@ObservesAsync` + reactive engine). In production the engine processes one lifecycle event per case at a time, so sequence numbers are assigned without races.

## Execution Models

casehub-engine is a **hybrid choreography+orchestration engine**. Both models share the same worker selection infrastructure (`WorkBroker`, `WorkerSelectionStrategy`, `WorkloadProvider`) and the same Quartz execution layer.

### Choreography (Binding-Driven)

Context changes trigger binding evaluations. When a binding's condition is met, `CaseContextChangedEventHandler` builds a `WorkerCandidate` list from capable workers, calls `WorkBroker.apply()` with `LeastLoadedStrategy`, and publishes a `WorkerScheduleEvent` for the selected worker. If no pre-defined workers match a capability, the engine calls `tryProvision()` to attempt dynamic provisioning via the registered `WorkerProvisioner` SPI. The case remains `RUNNING` throughout.

```
CaseContext change
  → CaseContextChangedEventHandler.publishByTarget()
      CapabilityTarget → WorkBroker.apply(LeastLoadedStrategy) → WorkerScheduleEvent
      SubCaseTarget    → SubCaseScheduleEvent
      HumanTaskTarget  → inputMapping evaluated → HumanTaskScheduleEvent
      ExtensionTarget  → warning log, no dispatch

  [CapabilityTarget path]
  → WorkerScheduleEvent → WorkerScheduleEventHandler
      → WorkerContextProvider.buildContext()             [always called]
      → Quartz
  → WorkflowExecutionCompleted → CaseContext updated → next binding fires

  → tryProvision(caseInstance, capability)               [no candidates]
      → WorkerProvisioner.provision() if capability advertised
      → ProvisioningException caught; binding stays eligible
```

**Semantics:**
- No case suspension. Work flows continuously.
- Bindings are passive (triggered by context change), not imperative.
- Worker order emerges from dependency, not direction.
- All capable workers compete for selection; LeastLoadedStrategy picks the least-loaded.

### Orchestration (Explicit Work Submission)

`WorkOrchestrator.submit(CaseInstance, WorkRequest)` selects a worker via `WorkBroker`, publishes a `WorkerScheduleEvent`, and returns a `CompletionStage<WorkResult>`. `WorkOrchestrator.submitAndWait()` additionally suspends the case to `WAITING`; `WorkflowExecutionCompletedHandler` resumes it when the matching worker completes.

```
WorkOrchestrator.submitAndWait(instance, request)
  → WorkBroker selects worker
  → WORK_SUBMITTED written to EventLog (durable)
  → case transitions to WAITING, waitingForWorkId persisted
  → WorkerScheduleEvent → Quartz executes worker
  → WorkflowExecutionCompleted fires
  → WorkflowExecutionCompletedHandler: WAITING → RUNNING, WORK_COMPLETED written
  → PendingWorkRegistry.complete() → CompletionStage<WorkResult> resolves
  → CONTEXT_CHANGED fires (case now RUNNING) → bindings re-evaluate
```

**Semantics:**
- Explicit caller initiates work (not a binding).
- Caller receives a `CompletionStage<WorkResult>` to wait on externally.
- Optionally transitions the case to WAITING (for critical milestones).
- Survives JVM restart: correlation key persists, futures re-registered on startup.

### Worker Selection (Shared Infrastructure)

| Component | Role |
|---|---|
| `WorkBroker` (quarkus-work-core) | Trigger gate + capability filter + strategy dispatch |
| `LeastLoadedStrategy` (quarkus-work-core) | Selects worker with fewest active Quartz jobs |
| `CasehubWorkloadProvider` | Counts active Quartz jobs per worker name |
| `NoOpWorkerRegistry` (quarkus-work-core) | Group resolution (no-op; workers come from CaseDefinition) |

All selection paths converge on `WorkBroker.apply()`:
- **Input:** `SelectionContext` (workload type, filters), `AssignmentTrigger` (CREATED), `WorkerCandidate` list (capability-filtered workers with load counts), `WorkerSelectionStrategy` (LeastLoadedStrategy)
- **Output:** `AssignmentDecision` (either `assignTo(workerId)` or `noChange()`)

### SubCaseBinding (casehub-blackboard)

A `Binding` with a `subCase` field (mutually exclusive with `capability`) spawns a child
`CaseInstance` when its trigger fires.

**Model fields on `SubCase`:**
- `inputMapping` (JQ object template, e.g. `{ key: .key }`): evaluated against parent context → child initial context
- `outputMapping` (JQ object template, default null): evaluated against child final context → merged to parent
- `waitForCompletion` (default true): parent transitions to WAITING; resumes on child terminal

**Engine wiring:**
- `CaseContextChangedEventHandler` detects `binding.target() instanceof SubCaseTarget` and publishes `SubCaseScheduleEvent` (skips worker selection entirely)
- `SubCaseExecutionHandler` (casehub-blackboard) consumes the event on a blocking worker thread, spawns child via `CaseHubRuntime`, transitions the PlanItem to `DELEGATED` (engine is idle; waiting for child signal), indexes `childCaseId → planItemId` for completion routing, writes `SUBCASE_STARTED` EventLog. Error paths (circular dependency, missing definition, spawn failure) fault the PlanItem; stuck PlanItems prevent re-scheduling.
- `SubCaseCompletionService` (casehub-blackboard) resumes the parent case then publishes `SubCaseExecutionCompleted`; `PlanItemCompletionHandler` consumes this and marks the PlanItem `COMPLETED`, triggering stage autocomplete on the same path as worker completion.

**`PlanItemStatus` for non-capability bindings:** `DELEGATED` (not `RUNNING`) is the correct active state for SubCase, HumanTask, and Extension PlanItems. `RUNNING` is reserved for CapabilityTarget bindings where a Quartz thread is actively executing.

**EventLog entries:** `SUBCASE_STARTED` (on spawn, metadata: childCaseId, waitForCompletion), `SUBCASE_COMPLETED` (on child terminal, metadata: childCaseId, childFinalStatus)

**Circular detection:** child definition matching parent definition is rejected with an error log — PlanItem is faulted.

### HumanTaskBinding (casehub-engine-work-adapter)

A `Binding` with a `HumanTaskTarget` routes to a human WorkItem in casehub-work when its trigger fires. Two creation modes:

- **Inline** — self-contained task definition (`title`, `candidateGroups`, `expiresIn`, `scope`)
- **Template** — references a `WorkItemTemplate` by ID; `scope` set manually on the WorkItem

**Engine wiring:**
- `CaseContextChangedEventHandler` detects `binding.target() instanceof HumanTaskTarget`, evaluates `inputMapping` against `CaseContext`, and publishes `HumanTaskScheduleEvent` on `casehub.humantask.schedule`
- `HumanTaskScheduleHandler` (work-adapter, `@ConsumeEvent(blocking=true)`) looks up the `PlanItem` by binding name via `CasePlanModel.getPlanItemByBindingName()`, marks it `DELEGATED` (control passed to human actor — not `RUNNING`, which is reserved for Quartz-executed CapabilityTarget workers), creates a `WorkItem` via `WorkItemService` with `callerRef = case:{caseId}/pi:{planItemId}` and `scope = target.scope()`
- `WorkItemLifecycleAdapter` extended: on WorkItem completion, evaluates `outputMapping` against the resolution JSON (not the CaseContext) and calls `CaseContext.setAll()` before firing `CONTEXT_CHANGED`

**Data flow:**
```
CaseContext condition → HumanTaskScheduleEvent (inputData pre-evaluated)
  → HumanTaskScheduleHandler: PlanItem.markDelegated(), WorkItem.create(callerRef)
  → Human acts → WorkItem COMPLETED
  → WorkItemLifecycleEvent(callerRef)
  → WorkItemLifecycleAdapter: PlanItem.markCompleted(), outputMapping → CaseContext
  → CONTEXT_CHANGED → bindings re-evaluate
```

**callerRef format:** `case:{caseId}/pi:{planItemId}` — same format as the automated worker path.

### Durability (Orchestration Only)

`PendingWorkRegistry` survives JVM restarts by scanning the EventLog on startup for `WORK_SUBMITTED` events without `WORK_COMPLETED` and re-registering futures. `WorkerExecutionRecoveryService` replays the Quartz jobs; both mechanisms work together to restore in-flight orchestrated work.

The `waitingForWorkId` column on `CaseInstanceEntity` persists the correlation between a WAITING case and its in-flight work, enabling WAITING→RUNNING resumption after restart.

**EventLog entries for orchestrated work:**
- `WORK_SUBMITTED` — when `WorkOrchestrator.submit()` is called (metadata: `correlationKey`, `capability`)
- `WORK_COMPLETED` — when `WorkflowExecutionCompletedHandler` resumes a WAITING case (metadata: `correlationKey`, old/new status)

## Worker Execution Lifecycle

Full sequence from Quartz job fire to case context update:

```
Quartz fires job
  → WorkerExecutionJobListener.jobToBeExecuted()
      → WorkerStatusListener.onWorkerStarted(workerId, {caseId})
      → EventLog: WORKER_EXECUTION_STARTED (async persist)
  → WorkerExecutionTask.execute()
      → load EventLog by ID
      → load CaseInstance (cache or restore)
      → resolve Worker and Capability from CaseDefinition
      → execute worker function (Workflow, Function<Map,Map>, or Agent)
      → publish WorkflowExecutionCompleted → WORKER_EXECUTION_FINISHED bus
  → WorkflowExecutionCompletedHandler.onWorkflowExecutionCompletedHandler()
      → snapshot CaseContext (before)
      → apply output with conflict resolution strategy
      → snapshot CaseContext (after), compute contextDiff
      → EventLog: WORKER_EXECUTION_COMPLETED (payload=output, metadata=inputDataHash+contextChanges)
      → resumeIfWaiting() — if case WAITING and correlationKey matches:
          → case RUNNING, EventLog: WORK_COMPLETED, PendingWorkRegistry.complete()
      → WorkerStatusListener.onWorkerCompleted(workerId, WorkResult)
      → CaseLifecycleEvent: WorkerExecutionCompleted (async CDI)
      → publish CONTEXT_CHANGED → bindings re-evaluate
```

**Conflict resolution on output:** Each output key is written through the `ContextDiffStrategy`-selected resolver configured on the binding (`LAST_WRITER_WINS` default, `FIRST_WRITER_WINS`, or `FAIL`).

**Idempotency:** `WorkerScheduleEventHandler` holds a Vert.x local lock on `(caseId, workerId, inputDataHash)` and checks for existing `WORKER_SCHEDULED / WORKER_EXECUTION_STARTED / WORKER_EXECUTION_COMPLETED` events before submitting to Quartz. Duplicate `CONTEXT_CHANGED` events that arrive while a worker is in flight are silently dropped.

## Worker Function Types

Workers can be implemented using three function types:

### Workflow

Serverless Workflow execution via `io.serverlessworkflow.api.types.Workflow`. The workflow model is executed by `WorkflowExecutor` and returns a `WorkflowModel` containing the output state.

### Function

Java function with signature `Function<Map<String, Object>, Map<String, Object>>`. The function receives input data derived from the CaseContext via the capability's `inputSchema` and returns output data that is merged back via the `outputSchema`.

### Agent

AI-powered worker using LangChain4j models. Agents transform input data using JQ expressions, invoke an LLM with a system prompt, and transform the LLM response back to output data.

**Agent architecture:**

```
CaseContext
  → inputSchema (JQ) → transformed input
  → system prompt + user message template
  → ChatModel.chat(request)
  → LLM response (JSON)
  → outputSchema (JQ) → output data
  → merge to CaseContext
```

**Key components:**
- `Agent` — core execution class; applies JQ transformations, builds ChatRequest, invokes model
- `ChatModelProvider` — SPI interface (ServiceLoader-based) for pluggable LLM backends
- `ModelType` enum — OPENAI, OLLAMA, ANTHROPIC, MISTRAL_AI, GOOGLE_AI_GEMINI
- `JqTransformer` — applies JQ expressions for input/output mapping
- Provider implementations use reflection to avoid compile-time dependencies on vendor SDKs

**Execution:**
- Runs in `CompletableFuture.supplyAsync()` with timeout enforcement
- `WorkerExecutionContext` thread-local is set before Agent.execute() and cleared in finally block
- Same retry/failure semantics as Function and Workflow types

**Builder API:**

```java
Agent agent = Agent.builder()
    .systemPrompt("Analyze sentiment...")
    .inputSchema("{ text: .text }")
    .outputSchema("{ sentiment: .sentiment }")
    .model(ModelType.OPENAI)  // or ChatModelProvider, or ChatModel
    .build();

Worker worker = Worker.builder()
    .name("sentiment-analyzer")
    .capabilities(capability)
    .function(agent)
    .build();
```

## Failure and Retry Lifecycle

When a Quartz job throws, the retry-or-fault sequence is:

```
WorkerExecutionTask.execute() throws
  → WorkerExecutionJobListener.jobWasExecuted(context, exception)
      → EventLog: WORKER_EXECUTION_FAILED (metadata=inputDataHash+errorMessage)
      → maybeRescheduleJob()
          → load RetryPolicy from Worker.executionPolicy.retries
          → count WORKER_EXECUTION_FAILED events for (caseId, workerId, inputDataHash)
          → if failureCount < retryPolicy.maxAttempts:
              → compute delay (FIXED | EXPONENTIAL | EXPONENTIAL_WITH_JITTER)
              → WorkerExecutionScheduler.scheduleRetry()
          → else:
              → publish WorkerRetriesExhaustedEvent → WORKER_RETRIES_EXHAUSTED bus
  → WorkerRetriesExhaustedEventHandler.onWorkerRetriesExhaustedEvent()
      → set CaseInstance.state = FAULTED
      → caseInstanceRepository.updateStateAndAppendEvent()    [atomic]
          → EventLog: CASE_FAULTED
      → WorkerStatusListener.onWorkerStalled(workerId)
      → publish CASE_STATUS_CHANGED
  → CaseStatusChangedHandler.onCaseStatusChangedHandler()
      → CaseChannelProvider.closeChannel() for all open channels
      → SchedulerService.cancelAllTriggers(caseId)
      → publish CASE_FAULTED bus address
```

**Retry count source:** `EventLogRepository.findByCaseAndWorkerAndType(WORKER_EXECUTION_FAILED)` filtered by `inputDataHash` metadata — counts distinct failed attempts for the same logical invocation.

**Backoff strategies:**
- `FIXED` — constant `delayMs`
- `EXPONENTIAL` — `delayMs × 2^(attempt-1)`, capped at 30 s
- `EXPONENTIAL_WITH_JITTER` — random in `[0, exponential cap]`

**Guard quarantine:** `WorkerExecutionGuard.isBlocked()` is checked in `WorkerScheduleEventHandler` before event log creation. Quarantined workers immediately emit `WorkerRetriesExhaustedEvent` without scheduling a Quartz job.

## EventLog Event Sequence

Every significant decision is recorded with full provenance. The table below shows events in emission order for a complete successful case, plus the variants for failure and orchestration.

### Successful choreography case

| # | EventLog type | Writer | Notes |
|---|---|---|---|
| 1 | `CASE_STARTED` | `CaseStartedEventHandler` | Payload: initial context snapshot |
| 2 | `WORKER_SCHEDULED` | `WorkerScheduleEventHandler` | Metadata: workerName, capabilityName, inputDataHash |
| 3 | `WORKER_EXECUTION_STARTED` | `WorkerExecutionJobListener` | Metadata: inputDataHash |
| 4 | `WORKER_EXECUTION_COMPLETED` | `WorkflowExecutionCompletedHandler` | Payload: output; metadata: inputDataHash, contextChanges |
| 5 | `CASE_STATUS_CHANGED` → `CASE_COMPLETED` | `CaseStatusChangedHandler` | Written when goal expression satisfied |

### Failure variant (retries exhausted)

After step 3 above, for each failed attempt:

| # | EventLog type | Writer | Notes |
|---|---|---|---|
| 4a | `WORKER_EXECUTION_FAILED` | `WorkerExecutionJobListener` | Metadata: inputDataHash, errorMessage |
| (repeat 4a per attempt until maxAttempts) | | | |
| 5a | `CASE_FAULTED` | `WorkerRetriesExhaustedEventHandler` | Atomic with state transition |

### Orchestration variant (WAITING/RUNNING)

Between steps 2 and 3 above, `WorkOrchestrator.submitAndWait()` inserts:

| # | EventLog type | Writer | Notes |
|---|---|---|---|
| 2a | `WORK_SUBMITTED` | `WorkOrchestrator` | Metadata: correlationKey, capability |
| (case → WAITING) | `CASE_STATUS_CHANGED` | `CaseStatusChangedHandler` | |
| (after step 4) | `WORK_COMPLETED` | `WorkflowExecutionCompletedHandler.resumeIfWaiting()` | Metadata: correlationKey |
| (case → RUNNING) | `CASE_STATUS_CHANGED` | `CaseStatusChangedHandler` | |

The EventLog is append-only and immutable — a complete audit trail of the case's decision history.

## Naming Conventions

See **ADR-0003** (`adr/0003-work-workitem-task-naming.md`) for the formal decision. Summary:

| Term | Meaning |
|---|---|
| **Work** | Generalized assignable unit (automated or human) — the top-level concept |
| **WorkBroker** | Routes Work to the right worker (quarkus-work-api SPI) |
| **WorkItem** | Human-inbox specialisation of Work (requires claim/inbox semantics) |
| **Task** | Sub-steps within a Work unit (lowest granularity) |

casehub-engine uses `WorkBroker` from `quarkus-work-api` (shared SPI with quarkus-workitems) and `WorkOrchestrator` as the top-level orchestration API. This replaces the casehub-core `TaskBroker` (retired terminology).

## Case Lifecycle

```
PENDING (case created, not yet started)
  → RUNNING (bindings evaluated, work flows)
    → WAITING (orchestrated work in flight, case suspended; only from RUNNING via submitAndWait)
      → RUNNING (work completed, case resumes)
    → COMPLETED (all goals reached, case successful)
    → FAULTED (binding threw, work failed, or explicit error)
    → CANCELLED (case cancelled explicitly)
```

Only orchestration transitions a case to WAITING. Choreography keeps the case RUNNING unless an error occurs.

## Dependencies and SPI

The engine defines clean extension points via SPIs:

- **`CaseInstanceRepository`** — persist and retrieve case state
- **`EventLogRepository`** — persist and query the event log; supports filtering by event types and stream types via `findByCaseWithFilters()`
- **`CaseMetaModelRepository`** — retrieve case definitions

External systems implement these SPIs to provide storage. The engine depends only on the SPIs, not on specific storage backends.

### Worker Provisioner SPIs

Four dual-stack SPI interfaces (blocking + reactive) enable external systems to provision workers, observe lifecycle events, create channels for inter-worker communication, and build worker startup context from case lineage.

| Blocking SPI | Reactive Mirror | Purpose |
|---|---|---|
| `WorkerProvisioner` | `ReactiveWorkerProvisioner` | Provision/terminate workers when no pre-defined workers match a capability |
| `WorkerStatusListener` | `ReactiveWorkerStatusListener` | Lifecycle callbacks: `started()`, `completed()`, `stalled()` |
| `CaseChannelProvider` | `ReactiveCaseChannelProvider` | Open/close/post to backend-agnostic channels (Qhorus, Slack, etc.). `postToChannel` takes a 4th `MessageType` parameter (from `casehub-qhorus-api`) expressing message intent; a 3-arg default delegates with `null` for backward compatibility |
| `WorkerContextProvider` | `ReactiveWorkerContextProvider` | Build startup context from `CaseLedgerEntry` lineage — includes prior worker summaries, causal chain metadata |

**Model types** in `api/model/`:
- `CaseChannel` — backend-agnostic channel reference with extensible `properties` map
- `WorkerSummary` — prior worker's execution summary, includes `ledgerEntryId` (UUID of the `WORKER_EXECUTION_COMPLETED` ledger entry)
- `WorkerContext` — startup context handed to a worker at execution time; includes `channels` (all open channels for the case, from `CaseChannelProvider.listChannels(caseId)`), `priorWorkers` list, `caseId`, and causal chain metadata
- `WorkerExecutionContext` — thread-local holder set by `QuartzWorkerExecutionJob` immediately before calling the worker function; cleared in a `finally` block after execution. Workers call `WorkerExecutionContext.current()` to access their `WorkerContext` (including channels) at runtime
- `ProvisionContext` — input to `WorkerProvisioner.provision()`, contains the work request and case metadata. Fields: `caseId`, `taskType`, `workerContext` (nullable), `propagationContext`, `triggerChannelId` (nullable String — Qhorus channel ID of the COMMAND that triggered provisioning), `triggerCorrelationId` (nullable String — Qhorus correlation ID). Engine-internal call sites pass `null` for both trigger fields until engine#231 threads Qhorus trigger context through the CaseFile-update API

**Channel layering:** casehub-engine does not own the Channel concept — that belongs to Qhorus. `CaseChannelProvider` is a thin bridge associating channels with case lifecycle: open on case start, close on terminal state, post for worker messages. Backend variety (Qhorus, Slack, WhatsApp, DB) is entirely a Qhorus concern — zero engine changes when a new backend is added. See casehubio/qhorus#131 for the generalised Channel design and casehubio/engine#220 for the SPI contract.

**`casehub-qhorus-api` dependency:** The `api` module depends on `casehub-qhorus-api` (managed in root `pom.xml`) to import `MessageType` for the `postToChannel` signature. `MessageType` encodes the intent of a channel message at the protocol level (e.g. `COMMAND`, `RESPONSE`). See engine#230 for the longer-term plan to extract `MessageType` to a dedicated protocol artifact.

```
Backend (Qhorus / Slack / WhatsApp / DB)
    ↓
CaseChannelProvider SPI        ← engine's only concern
    ↓
WorkerContext.channels()       ← channels available to the worker
    ↓
WorkerExecutionContext.current() ← accessed by the worker function at runtime
```

**Default implementations** in `engine/internal/worker/`:
- `NoOpWorkerProvisioner` — throws `ProvisioningException` (never called unless provisioner advertises capabilities)
- `NoOpWorkerStatusListener` — silently ignores all lifecycle events
- `NoOpCaseChannelProvider` — returns sentinel channels with `backendType = "none"`; `postToChannel` and `closeChannel` are no-ops; `listChannels` returns empty list
- `EmptyWorkerContextProvider` — injects `CaseChannelProvider`; populates `channels` via `listChannels(caseId)` and `caseId` on the context; `priorWorkers` is empty (no ledger query)
- Four `@Alternative` reactive mirrors for optional reactive pipeline use

**Causal chain:** When a worker completes, `CaseLedgerEventCapture` writes a `WORKER_EXECUTION_COMPLETED` ledger entry. The `WorkerSummary` for that worker carries this entry's UUID as `ledgerEntryId`. New workers set `causedByEntryId` on their own ledger entries to this value, completing the causal chain across workers on a case.

**SPI placement rule:** Operational SPIs (worker provisioning, lifecycle, channels) go in `api/spi/`; persistence SPIs (`CaseMetaModelRepository`, etc.) go in `engine-model/spi/`.

### AI Agent Dependencies

The `api` module depends on `dev.langchain4j:langchain4j` (core) for the Agent worker type. Test scope includes provider implementations: `langchain4j-open-ai`, `langchain4j-ollama`, `langchain4j-anthropic`, `langchain4j-mistral-ai`, `langchain4j-google-ai-gemini`.

Provider implementations use reflection to load vendor SDKs at runtime, avoiding compile-time dependencies. The `ChatModelProvider` SPI is registered via `META-INF/services/io.casehub.api.model.ai.ChatModelProvider`.

### SPI Call Sites

All engine SPI call sites, in lifecycle order:

| SPI method / action | Called in | When |
|---|---|---|
| `CaseChannelProvider.openChannel` | `CaseStartedEventHandler.onCaseStarted` | Case transitions to RUNNING |
| `CaseChannelProvider.openChannel` + `postToChannel(..., MessageType.COMMAND)` | `WorkerScheduleEventHandler.dispatchCommand` | Worker scheduled — opens worker-specific channel, posts Qhorus COMMAND. Content fields: `type`, `capability`, `correlationId`, `input`, `deadline` (optional ISO-8601 Instant — present when `PropagationContext` has a budget, absent otherwise; consumed by claudony to bound Qhorus Commitment `expiresAt`) |
| `WorkerContextProvider.buildContext` | `WorkerScheduleEventHandler.onWorkerScheduleEventHandler` | Before Quartz job is submitted (timing contract) |
| `WorkerProvisioner.provision` | `CaseContextChangedEventHandler.tryProvision` | No pre-defined workers match capability AND provisioner advertises it |
| `WorkerStatusListener.onWorkerStarted` | `WorkerExecutionJobListener.jobToBeExecuted` | Quartz job begins execution |
| `WorkerContextProvider.buildContext` + `WorkerExecutionContext.set` | `QuartzWorkerExecutionJob.execute` | Immediately before worker function is invoked — populates channels, sets thread-local |
| `WorkerStatusListener.onWorkerCompleted` | `WorkflowExecutionCompletedHandler` | Worker function returns successfully |
| `WorkerStatusListener.onWorkerStalled` | `WorkerRetriesExhaustedEventHandler` | All retries exhausted; case transitions to FAULTED |
| `CaseChannelProvider.closeChannel` | `CaseStatusChangedHandler` | Case reaches terminal state (COMPLETED / FAULTED / CANCELLED) |

**COMMAND content schema:** The `dispatchCommand` content JSON is the only channel through which the engine can pass runtime context to consumers (e.g. claudony) — consumers have no direct access to `CaseInstance` or `PropagationContext`. The schema is currently a raw `Map`; typed formalisation is tracked in engine#301.

`WorkerProvisioner.provision()` is guarded by `getCapabilities()` — the no-op default returns empty set, so it is never called unless a real provisioner is wired in. `ProvisioningException` is caught and logged; the binding stays eligible for the next tick.

## Public API

### Event Log Query API

`CaseHubRuntime` exposes three overloaded `eventLog()` methods for retrieving case audit history:

```java
// All events for a case
CompletionStage<List<CaseEventLogRecord>> eventLog(UUID caseId)

// Filtered by event types
CompletionStage<List<CaseEventLogRecord>> eventLog(
    UUID caseId, 
    Set<CaseHubEventType> eventTypes)

// Filtered by event and stream types
CompletionStage<List<CaseEventLogRecord>> eventLog(
    UUID caseId, 
    Set<CaseHubEventType> eventTypes, 
    Set<EventStreamType> streamTypes)
```

All results are ordered by sequence number ascending. Null or empty filter sets return all events.

**DTO:** `CaseEventLogRecord` — public API model exposing the full EventLog structure including id, seq, eventType, streamType, workerId, timestamp, payload, and metadata.

## Configuration

Configuration uses the `casehub.` prefix. Key properties:

```properties
# Quartz (RAM store, no JDBC)
quarkus.quartz.store-type=ram

# Schema management (Hibernate drop-and-create, no migrations)
quarkus.hibernate-orm.schema-management.strategy=drop-and-create

# Event bus (Vert.x)
quarkus.vertx.event-loops=16

# Idempotency window (optional) — limits how far back the EventLog dedup check looks.
# Absent = permanent dedup (default, safest). Example: 7d
# casehub.idempotency.window=7d
```

See `src/main/resources/application.properties` for all available options.

## Testing

Tests use `@QuarkusTest` (never `*IT` classes). In-memory SPI implementations are provided for tests, eliminating Docker/database dependencies.

**Build and test:**

```bash
# Full suite
TESTCONTAINERS_RYUK_DISABLED=true mvn clean test

# Single module
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl engine

# Single test class
TESTCONTAINERS_RYUK_DISABLED=true mvn test -Dtest=ChoreographySelectionTest
```

## Resilience (`casehub-resilience`)

Optional module providing failure handling, conflict resolution, poison-pill detection, and timeout enforcement. Activated on the classpath alongside `engine`.

### Failure and Retry Lifecycle

Worker execution failures are captured as `WORKER_EXECUTION_FAILED` EventLog entries. `PoisonPillWorkerExecutionGuard` detects repeat failures on the same work item and routes them out of the normal execution loop. `BackoffDelayCalculator` computes exponential delays for retry scheduling. `CaseTimeoutEnforcer` monitors running cases and transitions them to `FAULTED` when a configured deadline is exceeded.

When retries are exhausted, `DeadLetterEventHandler` routes the entry to `DeadLetterQueue` with status `PENDING_REVIEW`.

### Dead Letter Queue Replay

When retries are exhausted, `DeadLetterEventHandler` routes the entry to `DeadLetterQueue` (PENDING_REVIEW). Two replay mechanisms are available:

**Explicit replay:** `DeadLetterReplayService.replay(deadLetterId)` recovers the original worker input from the `WORKER_SCHEDULED` EventLog entry and publishes a fresh `WorkerScheduleEvent`. Returns empty if the case is in a terminal state, the EventLog entry is missing, or the case definition cannot be resolved.

**Auto-replay:** `DeadLetterAutoReplayJob` (scheduled, disabled by default). Configuration:
- `casehub.dlq.auto-replay.enabled` (default: false)
- `casehub.dlq.auto-replay.interval` (default: PT30M)
- `casehub.dlq.auto-replay.delays` (default: PT30M,PT2H,PT8H)
- `casehub.dlq.auto-replay.max-attempts` (default: 3)

Entries that exhaust max-attempts stay PENDING_REVIEW for manual triage.

`DeadLetterEntry` tracks `replayAttempts` and `lastReplayAttemptAt` for eligibility evaluation.

## Roadmap

**Near term:**
- ✅ Hybrid choreography+orchestration (Q2 2026)
- ✅ WAITING state durability (Q2 2026)
- ✅ Immutable audit ledger (`casehub-ledger`, Q2 2026)
- ✅ DLQ replay — explicit API and optional auto-replay scheduler (Q2 2026)
- ✅ Worker Provisioner SPI wiring — all 4 blocking SPIs integrated (Q2 2026)
- ✅ `triggerChannelId` + `triggerCorrelationId` in `ProvisionContext` — causal linkage from Qhorus COMMAND to provisioning (engine#229, Q2 2026)
- [ ] Thread Qhorus trigger context through CaseFile-update API into `ProvisionContext` (engine#231)
- [~] Human worker integration — inline `HumanTaskTarget` dispatch complete (engine#245); template mode pending (engine#255) (Q2/Q3 2026)
- [ ] Escalation rules and thresholds (Q3 2026)

**Medium term:**
- [ ] Lineage-driven planning (learns from history)
- [ ] Cascade/parallel binding support (advanced scenarios)
- [ ] Sub-case delegation (nested cases)

**Long term:**
- [ ] Integration with Claudony (session management + dashboard)
- [ ] Integration with Qhorus (inter-agent communication)

## Further Reading

- **ADR-0001** — Blackboard model and terminology alignment
- **ADR-0002** — Binding evaluation strategy
- **ADR-0003** — Work/WorkItem/Task naming hierarchy
- **casehubio/engine#121** — Original design discussion (closed by ADR-0003)
- **casehubio/engine#131** — WorkBroker integration epic
- **casehubio/engine#145** — casehub-ledger integration epic
- **casehubio/engine#229** — `triggerChannelId` + `triggerCorrelationId` in `ProvisionContext`
- **casehubio/engine#231** — Thread Qhorus trigger context through CaseFile-update API (follow-on)
- **casehubio/engine#191** — Worker Provisioner SPI wiring
- **mdproctor/casehub-ledger#39** — CaseLedgerEntry tracking issue
