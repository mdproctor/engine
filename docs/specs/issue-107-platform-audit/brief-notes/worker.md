# Task Execution Runtime — Brief Notes

## Capability Area

**Pillar:** Automation
**Descriptive name:** Task Execution Runtime
**Lifecycle stage:** Intelligence + Control (worker functions execute AI/logic; Guard + SchemaValidator enforce policies)

## What It Does

Foundation primitive for automated task execution. A `Worker` is a named unit of computation with typed input/output, capability declarations, execution policies (retry, timeout, backoff), and JSON Schema validation. Workers are the executable atoms the Case Orchestration Engine dispatches.

## Key Capabilities

- **Typed Worker Functions** — `WorkerFunction<T,R>` with compile-time input/output parameterisation. Four variants: Sync, Persistent (event-loop), ExchangeProcessor (pipeline composition via `andThen()`), None (external proxy).
- **Sealed Outcome Model** — 5-variant `WorkerOutcome<R>`: Success, Declined, Failed, Expired, Completed. Exhaustive pattern matching. `PlannedAction` on Success enables structured follow-on actions.
- **Reasoning Capture** — `withReasoning(String)` on results; `reasoning()` on PersistentScope. Workers can attach chain-of-thought to every execution step. (AI-native design signal.)
- **JSON Schema Validation** — Input blocking, output warn-only (asymmetric by design). JSON Schema 2020-12 via networknt. Schema cache with ConcurrentHashMap.
- **Fault Tolerance** — SmallRye Guard with timeout, retry, exponential/jitter backoff. Guard instances cached by ExecutionPolicy (record equality). Timeout → Expired outcome. Exception → Failed outcome.
- **OTel Instrumentation** — Every execution traced with worker name, capability, and outcome attributes.
- **Exchange Pipelines** — `Exchange<T>` (body + headers + properties) enables worker-to-worker composition via `andThen()`. Typed pipeline building.
- **Data Channels** — `DataChannel<T>` for bidirectional inter-worker communication. `ChannelRef<T>` for type-safe references.

## Differentiation

- **Not just a function call.** Most orchestration frameworks treat tasks as simple callables. CaseHub workers are first-class entities with typed contracts, schema validation, fault tolerance, planned actions, and reasoning capture — all built into the execution model.
- **Sealed outcome algebra.** Five discriminated outcomes instead of success/error binary. Declined enables graceful routing (worker says "not me"). Expired and Completed separate timeout from clean shutdown. Pattern matching is exhaustive.
- **AI-native reasoning.** The reasoning field and PersistentScope.reasoning() method are designed for LLM workers that need to record chain-of-thought alongside results. This isn't bolted on — it's a first-class record field.
- **Pipeline composition.** ExchangeProcessor.andThen() builds typed worker pipelines with header/property propagation. No external pipeline framework needed.

## Extent

3 modules, 16 public API types, 3 runtime classes. Pure Java API (no Quarkus dependency). Jandex-indexed. Testing module with MockWorkerExecutor (mirrors validation but skips policy enforcement). 12 test classes across api and testing modules.

## Consistency Benefit

- `ExecutionPolicy`, `RetryPolicy`, `BackoffStrategy` live in `casehub-platform-api` — shared governance model across all platform capabilities
- `WorkerScope` defined here to avoid circular deps with engine-api; extended by engine's `WorkerRuntime`
- CDI discovery (`@ApplicationScoped`) with `@DefaultBean` displacement for testing — same pattern used across all platform repos
- OTel instrumentation follows platform-wide tracing conventions

## Platform Leverage

| Consumer | How it uses the Task Execution Runtime |
|----------|---------------------------------------|
| Case Orchestration Engine | Full execution path — dispatches Workers, passes WorkerRuntime as scope |
| Desired-State Reconciliation | Worker + Capability for node provisioning in the reconciliation graph |

## Vision (from open issues)

- **WorkerContext (#4)** — ambient execution state, reducing parameter threading. Would let workers access case context without explicit scope passing.
- **CDI cleanup (#12)** — 12 classes with unnecessary CDI annotations identified in platform audit. Lean primitive — further reduction.
- **Spring Boot deployment (#16, closed)** — core extraction + auto-configuration. Signals interest in deploying workers outside Quarkus.