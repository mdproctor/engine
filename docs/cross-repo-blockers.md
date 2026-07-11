# Cross-Repo Blockers — Engine Dependencies

Downstream issues across the casehubio ecosystem that are waiting on engine to deliver.

Last updated: 2026-07-10

---

## Active Blockers (engine issues still OPEN)

### LLM Supervisor Mode — engine#101 (L / High)

| Repo | # | Title | Scale | Complexity |
|------|---|-------|-------|------------|
| aml | #14 | AML Layer 8 — hard blocker for Layers 6 and 8 | XS | Low |
| aml | #72 | Gate rejection routing — MLRO rejection re-routing | — | — |
| clinical | #86 | Wire ProtocolAmendmentAdvisor to LlmPlanningStrategy | — | — |
| blocks | #10 | Full supervisor mode — LLM-driven orchestration | — | — |
| blocks | #13 | LlmDecomposition — goal-to-plan decomposition | — | — |
| parent | #310 | Umbrella epic for casehub-blocks | — | — |

### Agentic Planning Primitives — engine#694-698

| Engine # | Title | Scale | Complexity |
|----------|-------|-------|------------|
| #694 | DAG plan structure | L | High |
| #695 | Parallel execution driver | L | High |
| #696 | Multi-level recovery | L | High |
| #697 | Plan versioning | M | Med |
| #698 | Context isolation | M | Med |

Blocking 1 downstream epic:

| Repo | # | Title | Scale | Complexity |
|------|---|-------|-------|------------|
| blocks | #44 | Agentic planning architecture | — | — |

### Eidos Behavioral Contracts — engine#647 (M / Med), #632 (M / Med), #645 (M / Med)

| Repo | # | Title | Scale | Complexity |
|------|---|-------|-------|------------|
| eidos | #93 | Engine adoption of behavioral contracts framework | M | Low |

### Worker Lifecycle / Context — engine#237 (L / High), #419 (—)

| Repo | # | Title | Scale | Complexity |
|------|---|-------|-------|------------|
| casehub-worker | #3 | Worker execution model — async, timeout, context, validation | — | — |
| casehub-worker | #4 | WorkerContext — ambient execution state | M | Med |

### Other Single-Issue Blockers

| Engine # | Scale | Complexity | Downstream | Title |
|----------|-------|------------|------------|-------|
| #648 | S | Med | quarkmind#225 (— / —) | OutcomeRecorder.addAttestation |
| #501 | XL | High | parent#258 (XL / High) | Semantic failure routing epic |
| #633 | — | — | blocks#28 (— / —) | Worker data coordination patterns |

---

## Newly Unblocked (engine dependency CLOSED, downstream still open)

| Repo | # | Title | Scale | Complexity | Engine dep (CLOSED) |
|------|---|-------|-------|------------|---------------------|
| work | #287 | Retrofit work SPIs to NamedStrategy | M | Med | #634 |
| work | #237 | Structured progress tracking | L | High | #398/399/400 |
| aml | #61 | CBR over AML investigation patterns | — | — | #477/478 |
| clinical | #78 | CBR over adverse event history | — | — | #477/478 |
| quarkmind | #192 | CBR reference impl — CaseMemoryStore retention | — | — | #476/477/478 |
| quarkmind | #214 | CBR game experience — learning across games | XL | High | #478 |
| life | #56 | CBR suggestions into case plan execution | — | — | #478 |
| ledger | #172 | OutcomeRecord supplementary data | — | — | #650 |
| garden | #4 | Definable entity types/labels protocol | — | — | #652 |
| desiredstate | #61 | Aggregate subgraph reasoning | L | High | #652 |
| claudony | #99 | Channel gateway integration maturity | XL | High | #221 |
| scaffold | #24 | Quarkus-native developer experience | — | — | #235 |
