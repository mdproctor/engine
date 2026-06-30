# Worker Capability Names + YamlCaseHub Augmentation

**Issues:** engine#509 (already implemented — close), engine#591
**Date:** 2026-06-29

## Problem

Two related design gaps:

**1. Workers carry `List<Capability>` but only capability names are used.**

The engine matches workers to bindings by capability name. Every production usage of
`worker.capabilities()` either extracts names (`.stream().map(Capability::name)`) or
does name matching (`.stream().anyMatch(c -> c.name().equals(...))`). The `inputSchema`
and `outputSchema` on worker capabilities are never read — schemas come from the
binding's `CapabilityTarget`, not the worker.

This forces consumers to create redundant `Capability` instances (via a duplicated
`cap()` helper) just to carry a string.

**2. YamlCaseHub provides no augmentation hook.**

Consumers who need to add programmatic workers (backed by CDI-injected services) must
override `getDefinition()` and duplicate the double-checked lock. Three inconsistent
augmentation patterns exist across the platform:

- casehub-life (8 classes): DCL override + `augment()` + `cap()` helper — correct but duplicated
- casehub-aml (2 classes): `@PostConstruct` + descriptor delegation — ad-hoc
- casehub-devtown (PrReviewCaseHub): `@PostConstruct` mutation of `super.getDefinition()` — race condition

## Design

### Layer 1: Worker record — `Set<String> capabilityNames`

**Repo:** casehub-worker-api

Change the `Worker` record:

```java
// Before
public record Worker(String name, List<Capability> capabilities, WorkerFunction function,
                     ExecutionPolicy executionPolicy, String description) {}

// After
public record Worker(String name, Set<String> capabilityNames, WorkerFunction function,
                     ExecutionPolicy executionPolicy, String description) {}
```

`Set<String>` is the right type: every production usage does membership checks
(`contains` / `anyMatch(name.equals(...))`), capability names are unique per worker,
and duplicates should be rejected at construction time rather than silently accepted.

Builder methods:

| Before | After |
|--------|-------|
| `capabilities(Capability... c)` | `capabilityNames(String... names)` |
| `capabilities(List<Capability> c)` | `capabilityNames(Collection<String> names)` |
| `capability(Capability c)` | `capabilityName(String name)` |

The compact constructor validates `capabilityNames` is non-null and copies it
to an unmodifiable set via `Set.copyOf()` (deduplicates, rejects null elements).

**TestWorkerBuilder** (casehub-worker-testing, same repo): `sync()` changes from
`Worker.builder().capability(Capability.of(name, "{}", "{}"))` to
`.capabilityName(name)` — mechanical.

### Layer 2: YamlCaseHub template method

**Repo:** casehub-engine (`api/`)

`getDefinition()` becomes `final`. A `protected void augment(CaseDefinition)` hook
is called inside the DCL between YAML loading and caching:

```java
@Override
public final CaseDefinition getDefinition() {
    if (definition == null) {
        synchronized (this) {
            if (definition == null) {
                try (InputStream is = ...) {
                    CaseDefinition loaded = CaseDefinitionYamlMapper.load(
                        is, objectMapper, expressionEngineRegistry, workerFunctionProviderRegistry);
                    augment(loaded);
                    definition = loaded;
                }
            }
        }
    }
    return definition;
}

protected void augment(CaseDefinition definition) {
    // no-op default — subclasses override to add workers, descriptors, etc.
}
```

Consumers migrate from overriding `getDefinition()` to overriding `augment()`.
The `cap()` helper is replaced by `capabilityNames("name")` on the Worker builder.

### Layer 3: Engine production code updates

| File | Change |
|------|--------|
| `CaseDefinitionYamlMapper` | Pass `sw.getCapabilities()` directly as capability names (already strings in schema model). This eliminates a latent null-insertion bug: the current `capabilityMap::get` lookup inserts null when a worker references an undefined capability name. Add validation: warn when a worker references a capability name not defined in the `capabilities` section. Note: this warning may fire as a false positive for capability names that are intentionally defined programmatically in `augment()` — the mapper runs before `augment()`. |
| `AgentCandidateFactory` | `w.capabilityNames().contains(capName)` replaces stream-anyMatch |
| `WorkflowExecutionCompletedHandler` | `findMatchingCapabilityBinding()`: iterates bindings and checks if the worker has a matching capability — `worker.capabilities().stream().anyMatch(c -> c.name().equals(capabilityName))` becomes `worker.capabilityNames().contains(capabilityName)`. Same `.contains()` simplification but inside a binding-iteration loop (reverse lookup). |
| `SchedulerService` | Same `.contains()` simplification |
| `PlanningStrategyLoopControl` | Same `.contains()` simplification |
| `DeadLetterReplayService` | Read capability name from `originalScheduled.getMetadata().get("capabilityName")` (already stored by `WorkerScheduleEventHandler.buildEventLog()` at line 172), then resolve the authoritative `Capability` from `definition.getCapabilities()` by that name. This replaces the current `worker.capabilities().stream().findFirst()` which is both imprecise (picks an arbitrary capability instead of the one from the original execution) and non-deterministic with `Set.copyOf()`. |
| `QhorusMessageSignalBridge` | `.capabilityNames(Set.of())` |

### Layer 4: Test code

All `Worker.builder().capabilities(cap)` calls become `.capabilityNames("capName")`.
~130+ test call sites — mechanical migration.

## Issue #509

`Binding.inputSchemaOverride` is already fully implemented: API model, schema, YAML
parsing, builder, `effectiveInputSchema()`, runtime threading through
`WorkerScheduleEvent`, `CaseContextChangedEventHandler`, `WorkerScheduleEventHandler`,
and `tryProvision()`. Tests exist. Commit `373b4d75`. Close the issue.

### Capability stays in casehub-worker-api

After this change, no type in casehub-worker-api references `Capability`. A future
reader might conclude it's misplaced. It stays because:

1. **PLATFORM.md designates casehub-worker-api as the canonical worker vocabulary**
   — `Worker`, `Capability`, `WorkerFunction`, `WorkerResult`, `WorkerOutcome`,
   `PlannedAction`. `Capability` describes what a worker can do; it belongs with
   the worker identity primitives regardless of whether `Worker` carries the full
   object or just names.
2. **casehub-desiredstate depends on casehub-worker-api (not casehub-engine-api)**
   for `Capability` — it constructs `Capability` objects for binding targets in
   `CaseTransitionExecutor`. Moving `Capability` to casehub-engine-api would force
   desiredstate to depend on engine-api, breaking the layering (desiredstate is
   foundation tier, engine is orchestration tier).
3. **casehub-engine-api already depends on casehub-worker-api** — consuming
   `Capability` from worker-api is the natural direction. Moving it to engine-api
   would invert a dependency.

## Cross-repo migration (follow-on)

Consumer repos need to update their YamlCaseHub subclasses:

- **casehub-life** (8 classes): Override `augment()` instead of `getDefinition()`.
  Delete volatile field, DCL, and `cap()` helper. Use `capabilityNames("name")`
  on Worker builder. Tracked in casehub-life#47.
- **casehub-aml** (2 classes): Move `@PostConstruct` logic to `augment()`.
  Tracked in casehub-aml#TBD (issue to be created).
- **casehub-devtown** (2 classes): Move `@PostConstruct` logic to `augment()`.
  Fixes existing race condition in PrReviewCaseHub.
  Tracked in casehub-devtown#TBD (issue to be created).
- **casehub-desiredstate** (`CaseTransitionExecutor`): Change
  `Worker.builder().capabilities(dispatchCapability)` to
  `.capabilityName("desiredstate-dispatch")`. The `Capability` construction for
  `Binding.builder().capability()` is unaffected — `Binding` still takes `Capability`.
  Tracked in casehub-desiredstate#TBD (issue to be created).
- **casehub-clinical**, **casehub-claudony**: No augmentation — no change needed
  beyond recompile against new casehub-worker-api SNAPSHOT.

## Breaking changes

- `Worker.capabilities()` → `Worker.capabilityNames()` — returns `Set<String>` instead of `List<Capability>`; all callers must update
- `Worker.Builder.capabilities(Capability...)` → `.capabilityNames(String...)` — all builders must update
- `Worker.Builder.capability(Capability c)` → `.capabilityName(String name)` — singular form, most common in tests and single-capability workers
- `Worker.Builder.capabilities(List<Capability>)` → `.capabilityNames(Collection<String>)` — accepts any collection
- `YamlCaseHub.getDefinition()` is final — subclasses must use `augment()` instead
