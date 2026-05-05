# 0004 — Worker Provisioner SPIs in `api/spi/`, not `engine-model/spi/`

Date: 2026-04-23
Status: Accepted

## Context and Problem Statement

Four new SPI interfaces were needed to allow external systems to provision workers, observe worker lifecycle events, create channels for inter-worker communication, and build worker context from case lineage. The codebase already has two locations for SPI interfaces: `api/spi/` (used by operational SPIs like `WorkerExecutionGuard`, `ContextDiffStrategy`) and `engine-model/spi/` (used by persistence SPIs like `CaseMetaModelRepository`, `CaseInstanceRepository`, `EventLogRepository`). Following the dual-stack pattern established across the ecosystem (casehub, ledger, qhorus, workitems), each SPI needed both a blocking variant and a reactive (`Uni<>`-returning) mirror. The question: where should all eight interfaces live?

## Decision Drivers

* **Operational vs. persistence distinction** — The new SPIs are about external system integration (provisioning, lifecycle observation, channel creation), not about data durability. The existing `engine-model/spi/` is scoped specifically to persistence concerns.
* **Consistency with existing placement** — `WorkerExecutionGuard` and `ContextDiffStrategy` are operational SPIs already living in `api/spi/`.
* **Dual-stack pattern** — Both blocking and reactive variants follow the established pattern across the ecosystem; both should live together.
* **Causal clarity** — Grouping operational SPIs together (all in `api/spi/`) makes the rule clear and easy to remember for future SPIs.

## Considered Options

* **Option A — All eight in `api/spi/`** (four blocking + four reactive mirrors)
* **Option B — Blocking in `api/spi/`, reactive in `engine-model/spi/`**
* **Option C — All eight in `engine-model/spi/`** (treats worker provisioning as a persistence concern)

## Decision Outcome

Chosen option: **Option A**. `engine-model/spi/` is specifically for persistence SPIs. The new SPIs are not persistence — they enable external systems to *use* the engine, but do not govern how the engine stores data. They belong alongside other operational SPIs in `api/spi/`.

### Positive Consequences

* Clear rule: `api/spi/` = operational SPIs (external system integration), `engine-model/spi/` = persistence SPIs (data durability)
* Consistent with existing pattern (`WorkerExecutionGuard`, `ContextDiffStrategy` in `api/spi/`)
* All eight interfaces (four blocking + four reactive) live together, making the dual-stack pattern self-evident
* Easier to remember the rule: "External system integration goes in `api/spi/`"

### Negative Consequences / Tradeoffs

* Reactive variants in `api` introduce a Mutiny dependency there (but Mutiny is already present in the reactor stack; no new transitive cost)

## Pros and Cons of the Options

### Option A — All eight in `api/spi/`

* ✅ Clear operational vs. persistence distinction
* ✅ Consistent with existing operational SPIs
* ✅ Dual-stack pattern is co-located
* ✅ Clear rule for future SPIs
* ❌ Mutiny in `api` (already present; no real cost)

### Option B — Blocking in `api/spi/`, reactive in `engine-model/spi/`

* ✅ Avoids Mutiny in `api` (but Mutiny already present)
* ❌ Splits the dual-stack pair across modules
* ❌ Makes the pattern harder to see and understand
* ❌ Suggests both placement rules are equally valid (they're not)

### Option C — All eight in `engine-model/spi/`

* ✅ Consolidates SPI interfaces in one place
* ❌ Treats worker provisioning as a persistence concern (it's not)
* ❌ Violates the principle of clear module boundaries
* ❌ engine-model becomes a SPI dumping ground

## Links

* GitHub issue #153 — Worker Provisioner SPIs design and implementation
* DESIGN.md — Worker Provisioner SPIs subsection (describes the SPIs and placement rule)
* CLAUDE.md — Worker Provisioner SPIs section (developer workflow for adding new operational SPIs)
