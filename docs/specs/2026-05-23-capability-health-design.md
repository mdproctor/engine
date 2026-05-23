# Design Spec — CapabilityHealth integration (engine#341)

**Date:** 2026-05-23
**Issue:** casehubio/engine#341
**Branch:** issue-341-capability-health

## Problem

`WorkOrchestrator.buildCandidates()` treats all capability-matched workers as equally
capable. Agent-backed workers (LLM agents) may be unavailable, overloaded, or
epistemically weak for the task domain. Without health probing, the broker selects
from candidates that cannot fulfill the dispatch.

## Design

### Approach

Add `AgentDescriptor` (from `casehub-eidos-api`) as an optional field on `Worker`.
Inject `CapabilityHealth` into `WorkOrchestrator` with a `@DefaultBean` no-op fallback.
Probe agent-backed workers after the cheaper capability match and workload checks.

### Tier boundary

`casehub-engine-api` already depends on `dev.langchain4j:langchain4j` for the `Agent`
worker type. Adding `casehub-eidos-api` (pure Java, no CDI) is architecturally consistent.
`AgentDescriptor` is a construction-time property of the worker's identity — it belongs
on the model, not discovered at runtime via registry lookup.

## Changes

### 1. `casehub-engine-api` pom.xml

Add `casehub-eidos-api` as optional compile dependency:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-eidos-api</artifactId>
    <version>${version.io.casehub}</version>
    <optional>true</optional>
</dependency>
```

### 2. `Worker` — add `AgentDescriptor`

- `private final AgentDescriptor agentDescriptor` — nullable
- `Builder.agentDescriptor(AgentDescriptor)` — setter
- `AgentDescriptor agentDescriptor()` — accessor
- `boolean hasDescriptor()` — convenience, `return agentDescriptor != null`

**Null semantics:** no descriptor = non-agent worker (pure Java function). Skip probe,
assume capable. Non-agent workers are capable by construction.

### 3. `NoOpCapabilityHealth`

`@DefaultBean @ApplicationScoped` in `engine/internal/worker/`. Returns `Ready` for
all probes. Deployments without eidos get transparent no-op — no filtering, no dependency.

### 4. `WorkOrchestrator.buildCandidates()` — probe integration

After capability match and workload check, for workers with descriptors:

```java
if (worker.hasDescriptor()) {
    CapabilityStatus status = capabilityHealth.probe(
        worker.agentDescriptor(),
        capabilityName,
        ProbeContext.of(null));  // no task domain metadata yet
    // record status for sorting
}
```

**Filtering rules:**
- `Unavailable` → **hard filter** (remove from candidates). Log at WARN.
- `EpistemicallyWeak` → **preference demotion** (sort last, not removed). Log at INFO.
- `Degraded` → keep, sort after `Ready`. Log at DEBUG.
- `Ready` → keep, sort first. Log at DEBUG.

Candidates are returned as a preference-ordered list. If only `EpistemicallyWeak`
candidates remain, they are still dispatched — weak is better than nothing.

If all candidates are `Unavailable`, the list is empty → `WorkBroker.apply()` returns
no selection → task not dispatched. This matches the existing "no workers match
capability" path.

### 5. ProbeContext limitation

The engine does not currently have subject-domain metadata at dispatch time. `taskDomain`
is passed as `null`, `taskMetadata` is empty. This means `EpistemicallyWeak` will not
fire (epistemic domain keys like "java", "rust" won't match null). The filter becomes
effective when the engine threads richer context through binding metadata or case context.

## What doesn't change

- `CaseContextChangedEventHandler` choreography path — probe only applies to
  orchestrated dispatch via `WorkOrchestrator`
- `WorkBroker` — receives pre-sorted candidates, unaware of health
- `Agent` execution wrapper — no descriptor, no change
- `HumanTaskScheduleHandler` — human tasks have no agent descriptor

## Tests

### `WorkerTest`
- `hasDescriptor_withDescriptor_returnsTrue()`
- `hasDescriptor_withoutDescriptor_returnsFalse()`
- `agentDescriptor_accessor()`

### `WorkOrchestratorTest`
- `probe_unavailable_workerExcludedFromCandidates()` — inject recording
  CapabilityHealth returning Unavailable for one worker; assert excluded
- `probe_epistemicallyWeak_workerDemotedNotExcluded()` — weak candidate
  still in list, sorted last
- `probe_allUnavailable_emptyCandidateList()` — no candidates reach broker
- `probe_noDescriptor_probeSkipped()` — non-agent worker passes through
  without probing

### `NoOpCapabilityHealthTest`
- `probe_alwaysReturnsReady()`

### `DefaultWorkerSpiImplementationsTest`
- Add `NoOpCapabilityHealth` to beans table verification
