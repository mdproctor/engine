# Design Decisions

## D1: Watchdog alerts enter engine via a new SPI

**Choice:** New `StallRecoveryHandler` SPI in engine-common alongside `RecoveryCoordinator`
**Alternatives:**
- Extend RecoveryCoordinator — muddies the worker-failure contract, forces all 3 call sites to handle the new shape
- Synthetic worker failures — semantics are a lie (AGENT_STALE is not a worker failure)
- CDI event bridge only — no structured mapping, each handler decides ad hoc
**Rationale:** Watchdog alerts are a different failure class from worker outcomes. Separate SPI keeps both contracts clean.
**Trade-offs:** Two recovery SPIs to understand instead of one
**Sources:** RecoveryCoordinator.java, RecoveryContext.java, WatchdogAlertEvent.java
**Exploration:** quick
**Status:** captured

## D2: Condition-to-action mapping lives in engine-side config

**Choice:** `StallRecoveryPolicy` on `CaseDefinition`, per-condition overrides in YAML
**Alternatives:**
- Qhorus-side WatchdogAction — couples qhorus to engine recovery semantics
- Bridge module owns mapping — adds isolation but also a module just for config
**Rationale:** Engine owns recovery response semantics. Qhorus detects and delivers; engine decides what to do.
**Trade-offs:** CaseDefinition grows another policy object
**Sources:** RecoveryPolicy.java, CaseDefinition, WatchdogConditionType.java
**Exploration:** quick
**Status:** captured

## D3: Channel→case lookup for case resolution

**Choice:** Engine resolves affected cases from alert's `targetName` via a reverse channel→case index maintained by the bridge module
**Alternatives:**
- Broadcast to all active cases — noisy, agents serve multiple cases
- Require caseId on WatchdogAlertEvent — requires qhorus-api change
**Rationale:** CaseChannelProvider tracks case→channel (openChannel, listChannels) but has no reverse lookup. The bridge module must build and maintain a reverse index (channel name → caseId) by observing channel open/close events.
**Trade-offs:** Reverse index adds state to the bridge; alerts without channel context need a fallback (e.g., affectedAgentIds → active cases via WorkerExecutionManager)
**Sources:** CaseChannelProvider.java (openChannel, listChannels — no findCaseByChannel), WatchdogAlertEvent.java (targetName field)
**Exploration:** quick
**Status:** revised (decision review: reverse lookup must be built)

## D4: Break engine↔work circular dependency via SPI extraction + contributor relocation

**Choice:** Two refactorings to eliminate engine→work-runtime compile dependencies:
1. engine-inbound: replace direct WorkItemService call with an SPI in engine-common; work provides implementation
2. engine-actor-state: move WorkActorStateContributor to work repo (ActorStateContributor SPI is in platform-api)
**Alternatives:**
- Move work-engine-adapter back to engine — restores the pre-work#290 layout but puts work integration logic in the wrong repo
- Build-order profile — masks the problem, technical debt stays
**Rationale:** The cycle exists because two engine modules violate the boundary by calling work runtime directly. Fixing the violation is better than moving modules around.
**Trade-offs:** Cross-repo refactoring — requires a slot with both engine and work repos
**Sources:** casehub-engine-inbound/InboundWorkItemBridge.java, casehub-engine-actor-state/WorkActorStateContributor.java, issue #974
**Exploration:** deep-analysis
**Status:** captured

## D5: Watchdog bridge lives in a new optional module

**Choice:** New `casehub-engine-watchdog` module following the pattern of a2a, mcp, react, flow
**Alternatives:**
- Engine runtime — forces qhorus-api compile dependency on all consumers
**Rationale:** Not all consumers use qhorus. Optional module keeps the dependency isolated. SPI types (StallRecoveryHandler, StallRecoveryPolicy, StallRecoveryAction) in engine-api; implementation in the new module.
**Trade-offs:** One more module in the build
**Sources:** a2a/, mcp/, react/, flow/ module patterns
**Exploration:** quick
**Status:** captured

## D6: Flaky test root cause — missing timeout enforcement in FlowWorkerFunctionHandler

**Choice:** Fix the missing timeout: replace `.join()` with `.get(timeoutMs, TimeUnit.MILLISECONDS)`, handle `TimeoutException` → `WorkerResult.expired()`. Match the pattern from all other handlers. Clean up duplicate entries in application.properties. Remove `@Tag("flaky")`.
**Alternatives:**
- Increase Awaitility timeout only — masks the bug, handler still blocks indefinitely in production
- Add CDI indexing for flow module — wrong diagnosis, CDI discovery works fine
**Rationale:** Every other `WorkerFunctionHandler` enforces `timeoutMs` via `Future.get()`. `FlowWorkerFunctionHandler` receives the parameter but ignores it, calling `.join()` instead. Under CI load, the SWF workflow takes > 10s, `.join()` blocks indefinitely, case stays RUNNING.
**Trade-offs:** None — this is a straightforward bug fix
**Sources:** FlowWorkerFunctionHandler.java:74 (receives timeoutMs), :86 (.join()), SyncAgentWorkerFunctionHandler.java:149 (correct pattern)
**Exploration:** deep-analysis
**Status:** captured
